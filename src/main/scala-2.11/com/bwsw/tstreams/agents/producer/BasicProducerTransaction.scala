package com.bwsw.tstreams.agents.producer

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

import com.bwsw.tstreams.coordination.pubsub.messages.{ProducerTopicMessage, ProducerTransactionStatus}
import com.bwsw.tstreams.debug.GlobalHooks
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer

/**
  * Transaction retrieved by BasicProducer.newTransaction method
  *
  * @param transactionLock Transaction Lock for managing actions which has to do with checkpoints
  * @param partition       Concrete partition for saving this transaction
  * @param txnOwner        Producer class which was invoked newTransaction method
  * @param transactionUuid UUID for this transaction
  * @tparam USERTYPE User data type
  */
class BasicProducerTransaction[USERTYPE](transactionLock: ReentrantLock,
                                         partition: Int,
                                         transactionUuid: UUID,
                                         txnOwner: BasicProducer[USERTYPE]) {

  /**
    * State indicator of the transaction
    *
    * @return Closed transaction or not
    */
  def isClosed = closed

  /**
    * BasicProducerTransaction logger for logging
    */
  private val logger = LoggerFactory.getLogger(this.getClass)
  logger.debug(s"Open transaction for stream,partition : {${txnOwner.stream.getName}},{$partition}")

  /**
    *
    */
  def setAsClosed() =
    closed = true

  /**
    * Return transaction partition
    */
  def getPartition: Int = partition

  /**
    * Return transaction UUID
    */
  def getTxnUUID: UUID = transactionUuid

  /**
    * Return current transaction amount of data
    */
  def getCnt = part

  /**
    * Variable for indicating transaction state
    */
  private var closed = false

  /**
    * Transaction part index
    */
  private var part = 0

  /**
    * All inserts (can be async) in storage (must be waited before closing this transaction)
    */
  private var jobs = ListBuffer[() => Unit]()

  /**
    * Send data to storage
    *
    * @param obj some user object
    */
  def send(obj: USERTYPE): Unit = {
    transactionLock.lock()

    if (closed)
      throw new IllegalStateException("transaction is closed")

    txnOwner.producerOptions.insertType match {

      case InsertionType.BatchInsert(size) =>

        txnOwner.stream.dataStorage.putInBuffer(
          txnOwner.stream.getName,
          partition,
          transactionUuid,
          txnOwner.stream.getTTL,
          txnOwner.producerOptions.converter.convert(obj),
          part)

        if (txnOwner.stream.dataStorage.getBufferSize(transactionUuid) == size) {

          val job: () => Unit = txnOwner.stream.dataStorage.saveBuffer(transactionUuid)
          if (job != null) jobs += job
          txnOwner.stream.dataStorage.clearBuffer(transactionUuid)

        }

      case InsertionType.SingleElementInsert =>

        val job: () => Unit = txnOwner.stream.dataStorage.put(
          txnOwner.stream.getName,
          partition,
          transactionUuid,
          txnOwner.stream.getTTL,
          txnOwner.producerOptions.converter.convert(obj),
          part)
        if (job != null) jobs += job
    }

    part += 1
    transactionLock.unlock()
  }


  private def cancelAsync() = {
    transactionLock.lock()
    txnOwner.producerOptions.insertType match {
      case InsertionType.SingleElementInsert =>

      case InsertionType.BatchInsert(_) =>
        txnOwner.stream.dataStorage.clearBuffer(transactionUuid)
    }


    val msg = ProducerTopicMessage(txnUuid = transactionUuid,
      ttl = -1,
      status = ProducerTransactionStatus.cancel,
      partition = partition)

    txnOwner.masterP2PAgent.publish(msg)
    logger.debug(s"[CANCEL PARTITION_${msg.partition}] ts=${msg.txnUuid.timestamp()} status=${msg.status}")

    transactionLock.unlock()
  }

  /**
    * Canceling current transaction
    */
  def cancel() = {
    transactionLock.lock()

    if (closed)
      throw new IllegalStateException("transaction is already closed")

    closed = true

    txnOwner.backendActivityService.submit(new Runnable {
      override def run(): Unit = cancelAsync()
    })

    transactionLock.unlock()

  }

  private def checkpointPostEventPartSafe() = {
    try {
      checkpointPostEventPart()
    } catch {
      //        will be only in debug mode in case of precheckpoint failure test
      //        or postcheckpoint failure test
      case e: RuntimeException =>
        transactionLock.unlock()
    }
  }

  private def checkpointPostEventPart() = {
    logger.debug(s"[COMMIT PARTITION_$partition] ts=${transactionUuid.timestamp()}")

    //debug purposes only
    GlobalHooks.invoke("AfterCommitFailure")

    txnOwner.masterP2PAgent.publish(ProducerTopicMessage(
      txnUuid = transactionUuid,
      ttl = -1,
      status = ProducerTransactionStatus.postCheckpoint,
      partition = partition))

    logger.debug(s"[FINAL CHECKPOINT PARTITION_$partition] " +
      s"ts=${transactionUuid.timestamp()}")
  }

  private def checkpointAsyncSafe() = {
    try {
      checkpointAsync()
    } catch {
      //        will be only in debug mode in case of precheckpoint failure test
      //        or postcheckpoint failure test
      case e: RuntimeException =>
        transactionLock.unlock()
        throw e
    }
  }

  private def checkpointAsync() = {
    transactionLock.lock()
    txnOwner.producerOptions.insertType match {

      case InsertionType.SingleElementInsert =>

      case InsertionType.BatchInsert(size) =>
        if (txnOwner.stream.dataStorage.getBufferSize(transactionUuid) > 0) {
          val job: () => Unit = txnOwner.stream.dataStorage.saveBuffer(transactionUuid)
          if (job != null) jobs += job
          txnOwner.stream.dataStorage.clearBuffer(transactionUuid)
        }
    }
    //close transaction using stream ttl
    if (part > 0) {
      jobs.foreach(x => x())

      logger.debug(s"[START PRE CHECKPOINT PARTITION_$partition] " +
        s"ts=${transactionUuid.timestamp()}")

      txnOwner.masterP2PAgent.publish(ProducerTopicMessage(
        txnUuid = transactionUuid,
        ttl = -1,
        status = ProducerTransactionStatus.preCheckpoint,
        partition = partition))

      //debug purposes only
      GlobalHooks.invoke("PreCommitFailure")

      txnOwner.stream.metadataStorage.commitEntity.commitAsync(
        streamName = txnOwner.stream.getName,
        partition = partition,
        transaction = transactionUuid,
        totalCnt = part,
        ttl = txnOwner.stream.getTTL,
        executor = txnOwner.backendActivityService,
        function = checkpointPostEventPartSafe)

    }
    else {
      txnOwner.masterP2PAgent.publish(ProducerTopicMessage(
        txnUuid = transactionUuid,
        ttl = -1,
        status = ProducerTransactionStatus.cancel,
        partition = partition))
    }
    transactionLock.unlock()
  }

  /**
    * Submit transaction(transaction will be available by consumer only after closing)
    */
  def checkpoint(isSynchronous: Boolean = true): Unit = {
    if (closed)
      throw new IllegalStateException("transaction is already closed")

    closed = true

    if(isSynchronous == false) {
      transactionLock.lock()

      txnOwner.backendActivityService.submit(new Runnable {
        override def run(): Unit = checkpointAsyncSafe()
      })

      transactionLock.unlock()

    }
    else
    {
      transactionLock.lock()
      try {
        txnOwner.producerOptions.insertType match {

          case InsertionType.SingleElementInsert =>

          case InsertionType.BatchInsert(size) =>
            if (txnOwner.stream.dataStorage.getBufferSize(transactionUuid) > 0) {
              val job: () => Unit = txnOwner.stream.dataStorage.saveBuffer(transactionUuid)
              if (job != null) jobs += job
              txnOwner.stream.dataStorage.clearBuffer(transactionUuid)
            }
        }

        if (part > 0) {
          jobs.foreach(x => x())

          logger.debug(s"[START PRE CHECKPOINT PARTITION_$partition] " +
            s"ts=${transactionUuid.timestamp()}")

          txnOwner.masterP2PAgent.publish(ProducerTopicMessage(
            txnUuid = transactionUuid,
            ttl = -1,
            status = ProducerTransactionStatus.preCheckpoint,
            partition = partition))

          //debug purposes only
          GlobalHooks.invoke("PreCommitFailure")

          txnOwner.stream.metadataStorage.commitEntity.commit(
            streamName = txnOwner.stream.getName,
            partition = partition,
            transaction = transactionUuid,
            totalCnt = part,
            ttl = txnOwner.stream.getTTL)

          logger.debug(s"[COMMIT PARTITION_$partition] ts=${transactionUuid.timestamp()}")

          //debug purposes only
          GlobalHooks.invoke("AfterCommitFailure")

          txnOwner.masterP2PAgent.publish(ProducerTopicMessage(
            txnUuid = transactionUuid,
            ttl = -1,
            status = ProducerTransactionStatus.postCheckpoint,
            partition = partition))

          logger.debug(s"[FINAL CHECKPOINT PARTITION_$partition] " +
            s"ts=${transactionUuid.timestamp()}")

        }
        else
        {
          txnOwner.masterP2PAgent.publish(ProducerTopicMessage(
            txnUuid = transactionUuid,
            ttl = -1,
            status = ProducerTransactionStatus.cancel,
            partition = partition))
        }
      } catch {
        case e: RuntimeException =>
          transactionLock.unlock()
          throw e
      }
      transactionLock.unlock()
    }
  }

  private def doSendUpdateMessage() = {
    //publish that current txn is being updating
    txnOwner.subscriberClient.publish(ProducerTopicMessage(
      txnUuid = transactionUuid,
      ttl = txnOwner.producerOptions.transactionTTL,
      status = ProducerTransactionStatus.update,
      partition = partition), () => ())
    logger.debug(s"[KEEP_ALIVE THREAD PARTITION_${partition}] ts=${transactionUuid.timestamp()} status=${ProducerTransactionStatus.update}")

  }

  def updateTxnKeepAliveState() = {
    //-1 here indicate that transaction is started but is not finished yet
    logger.debug(s"Update event for txn ${transactionUuid}, partition: ${partition}")
    val f = txnOwner.stream.metadataStorage.commitEntity.commitAsync(
      streamName = txnOwner.stream.getName,
      partition = partition,
      transaction = transactionUuid,
      totalCnt = -1,
      ttl = txnOwner.producerOptions.transactionTTL,
      executor = txnOwner.backendActivityService,
      function = doSendUpdateMessage)
  }

  /**
    * accessor to lock object for external agents
    *
    * @return
    */
  def getTransactionLock(): ReentrantLock = transactionLock
}