package com.bwsw.tstreams.agents.producer

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

import com.bwsw.tstreams.agents.group.ProducerCheckpointInfo
import com.bwsw.tstreams.common.{LockUtil, ResettableCountDownLatch}
import com.bwsw.tstreams.debug.GlobalHooks
import com.bwsw.tstreams.proto.protocol.TransactionState
import com.bwsw.tstreamstransactionserver.rpc.TransactionStates
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer

object ProducerTransaction {
  val logger = LoggerFactory.getLogger(this.getClass)
}

/**
  * Transaction retrieved by BasicProducer.newTransaction method
  *
  * @param partition     Concrete partition for saving this transaction
  * @param producer      Producer class which was invoked newTransaction method
  * @param transactionID ID for this transaction
  */
class ProducerTransaction(partition: Int,
                          transactionID: Long,
                          producer: Producer) extends IProducerTransaction {

  private val transactionLock = new ReentrantLock()

  private val data = new ProducerTransactionData(this, producer.stream.ttl, producer.stream.client)

  private val isTransactionClosed = new AtomicBoolean(false)

  /**
    * This special trigger is used to avoid a very specific race, which could happen if
    * checkpoint/cancel will be called same time when update will do update. So, we actually
    * must protect from this situation, that's why during update, latch must be set to make
    * cancel/checkpoint wait until update will complete
    * We also need special test for it.
    */
  private val updateSignalVar = new ResettableCountDownLatch(0)

  /**
    * State indicator of the transaction
    *
    * @return Closed transaction or not
    */
  def isClosed() = isTransactionClosed.get

  /**
    * changes transaction state to updating
    */
  def setUpdateInProgress() = updateSignalVar.setValue(1)

  /**
    * changes transaction state to finished updating
    */
  def setUpdateFinished() = updateSignalVar.countDown

  /**
    * awaits while transaction updates
    *
    * @return
    */
  def awaitUpdateComplete(): Unit = {
    if (!updateSignalVar.await(10, TimeUnit.SECONDS))
      throw new IllegalStateException("Update takes too long (> 10 seconds). Probably failure.")
  }


  /**
    * BasicProducerTransaction logger for logging
    */
  ProducerTransaction.logger.debug(s"Open transaction $getTransactionID for\nstream, partition: ${producer.stream.name}, ${}")

  /**
    *
    */
  def markAsClosed() = isTransactionClosed.set(true)

  /**
    * Return transaction partition
    */
  def getPartition(): Int = partition


  override def toString(): String = s"producer.Transaction(ID=$transactionID, partition=$partition, count=${getDataItemsCount()})"

  /**
    * Return transaction ID
    */
  def getTransactionID(): Long = transactionID

  /**
    * Return current transaction amount of data
    */
  def getDataItemsCount() = data.lastOffset + data.items.size

  /**
    * Returns Transaction owner
    */
  def getTransactionOwner() = producer

  /**
    * All inserts (can be async) in storage (must be waited before closing this transaction)
    */
  private var jobs = ListBuffer[() => Unit]()


  /**
    * Send data to storage
    *
    * @param obj some user object
    */
  def send(obj: Array[Byte]): Unit = {
    if (isTransactionClosed.get())
      throw new IllegalStateException(s"Transaction $transactionID is closed. New data items are prohibited.")

    val number = data.put(obj)
    val job = {
      if (number % producer.producerOptions.batchSize == 0) {
        if (ProducerTransaction.logger.isDebugEnabled) {
          ProducerTransaction.logger.debug(s"call data save ${number} / ${producer.producerOptions.batchSize}")
        }
        data.save()
      }
      else {
        null
      }
    }
    if (job != null) jobs += job
  }

  def send(string: String): Unit = send(string.getBytes())

  /**
    * Does actual send of the data that is not sent yet
    */
  def finalizeDataSend(): Unit = {
    val job = data.save()
    if (job != null) jobs += job
  }


  /**
    * Canceling current transaction
    */
  def cancel(): Unit = this.synchronized {
    if (isTransactionClosed.get())
      throw new IllegalStateException(s"Transaction $transactionID is closed. New data items are prohibited.")

    awaitUpdateComplete
    isTransactionClosed.set(true)
    val transactionRecord = new RPCProducerTransaction(producer.stream.name, partition, transactionID, TransactionStates.Cancel, 0, -1L)

    producer.stream.client.putTransaction(transactionRecord, true)(rec => {})

    val msg = TransactionState(transactionID = transactionID,
      ttlMs = -1,
      status = TransactionState.Status.Cancelled,
      partition = partition,
      masterID = producer.getPartitionMasterIDLocalInfo(partition),
      orderID = -1,
      count = 0)
    producer.publish(msg)
  }

  private def checkpointPostEventPart(): Unit = {
    if (ProducerTransaction.logger.isDebugEnabled) {
      ProducerTransaction.logger.debug(s"[COMMIT PARTITION_{}] ts={}", partition, transactionID.toString)
    }

    //debug purposes only
    {
      val interruptExecution: Boolean = try {
        GlobalHooks.invoke(GlobalHooks.afterCommitFailure)
        false
      } catch {
        case e: Exception =>
          ProducerTransaction.logger.warn("AfterCommitFailure in DEBUG mode")
          true
      }
      if (interruptExecution)
        return
    }

    producer.publish(TransactionState(
      transactionID = transactionID,
      ttlMs = -1,
      status = TransactionState.Status.Checkpointed,
      partition = partition,
      masterID = producer.getPartitionMasterIDLocalInfo(partition),
      orderID = -1,
      count = getDataItemsCount()
    ))

    if (ProducerTransaction.logger.isDebugEnabled) {
      ProducerTransaction.logger.debug("[FINAL CHECKPOINT PARTITION_{}] ts={}", partition, transactionID.toString)
    }
  }


  private def checkpointAsync(): Unit = {
    //close transaction using stream ttl
    if (getDataItemsCount > 0) {
      jobs.foreach(x => x())

      if (ProducerTransaction.logger.isDebugEnabled) {
        ProducerTransaction.logger.debug("[START PRE CHECKPOINT PARTITION_{}] ts={}", partition, transactionID.toString)
      }

      //debug purposes only
      {
        val interruptExecution: Boolean = try {
          GlobalHooks.invoke(GlobalHooks.preCommitFailure)
          false
        } catch {
          case e: Exception =>
            ProducerTransaction.logger.warn("PreCommitFailure in DEBUG mode")
            true
        }
        if (interruptExecution) {
          return
        }
      }
      val transactionRecord = new RPCProducerTransaction(producer.stream.name, partition, transactionID, TransactionStates.Checkpointed, getDataItemsCount(), producer.stream.ttl)

      producer.stream.client.putTransactionWithData(transactionRecord, data.items, data.lastOffset, true)(record => {
        checkpointPostEventPart()
      })


    }
    else {
      producer.publish(TransactionState(
        transactionID = transactionID,
        ttlMs = -1,
        status = TransactionState.Status.Cancelled,
        partition = partition,
        masterID = producer.getPartitionMasterIDLocalInfo(partition),
        orderID = -1, count = 0))
    }
  }

  /**
    * Submit transaction(transaction will be available by consumer only after closing)
    */
  def checkpoint(isSynchronous: Boolean = true): Unit = {
    if (isTransactionClosed.get())
      throw new IllegalStateException(s"Transaction $transactionID is closed. New data items are prohibited.")
    LockUtil.withLockOrDieDo[Unit](transactionLock, (100, TimeUnit.SECONDS), Some(ProducerTransaction.logger), () => {
      awaitUpdateComplete()
      isTransactionClosed.set(true)

      if (!isSynchronous) {
        producer.asyncActivityService.submit("<CheckpointAsyncTask>", () => checkpointAsync(), Option(transactionLock))
      }
      else {
        if (getDataItemsCount() > 0) {
          jobs.foreach(x => x())

          if (ProducerTransaction.logger.isDebugEnabled) {
            ProducerTransaction.logger.debug("[START PRE CHECKPOINT PARTITION_{}] ts={}", partition, transactionID.toString)
          }

          //debug purposes only
          GlobalHooks.invoke(GlobalHooks.preCommitFailure)

          val transactionRecord = new RPCProducerTransaction(producer.stream.name,
            partition, transactionID, TransactionStates.Checkpointed, getDataItemsCount(),
            producer.stream.ttl)

          producer.stream.client.putTransactionWithDataSync(transactionRecord, data.items, data.lastOffset)

          if (ProducerTransaction.logger.isDebugEnabled) {
            ProducerTransaction.logger.debug("[COMMIT PARTITION_{}] ts={}", partition, transactionID.toString)
          }
          //debug purposes only
          GlobalHooks.invoke(GlobalHooks.afterCommitFailure)

          producer.notifyService.submit(s"NotifyTask-Part[${partition}]-Txn[${transactionID}]", () =>
            producer.publish(TransactionState(
              transactionID = transactionID,
              ttlMs = -1,
              status = TransactionState.Status.Checkpointed,
              partition = partition,
              masterID = producer.getPartitionMasterIDLocalInfo(partition),
              orderID = -1,
              count = getDataItemsCount())))

          if (ProducerTransaction.logger.isDebugEnabled) {
            ProducerTransaction.logger.debug("[FINAL CHECKPOINT PARTITION_{}] ts={}", partition, transactionID.toString)
          }
        }
        else {
          producer.notifyService.submit(s"NotifyTask-Part[${partition}]-Txn[${transactionID}]", () =>
            producer.publish(TransactionState(
              transactionID = transactionID,
              ttlMs = -1,
              status = TransactionState.Status.Cancelled,
              partition = partition,
              masterID = producer.getPartitionMasterIDLocalInfo(partition),
              orderID = -1,
              count = 0)))
        }
      }
    })
  }

  private def doSendUpdateMessage() = {
    {
      GlobalHooks.invoke(GlobalHooks.transactionUpdateTaskEnd)
    }

    setUpdateFinished

    producer.publish(TransactionState(
      transactionID = transactionID,
      ttlMs = producer.producerOptions.transactionTtlMs,
      status = TransactionState.Status.Updated,
      partition = partition,
      masterID = producer.transactionOpenerService.getUniqueAgentID(),
      orderID = -1,
      count = 0))

    if (ProducerTransaction.logger.isDebugEnabled) {
      ProducerTransaction.logger.debug(s"[KEEP_ALIVE THREAD PARTITION_PARTITION_$partition] ts=${transactionID.toString} status=Updated")
    }
  }

  def updateTransactionKeepAliveState(): Unit = {
    // atomically check state and launch update process
    val stateOnUpdateClosed =
      LockUtil.withLockOrDieDo[Boolean](transactionLock, (100, TimeUnit.SECONDS), Some(ProducerTransaction.logger), () => {
        val s = isClosed()
        if (!s) setUpdateInProgress()
        s
      })

    // if atomic state was closed then update process should be aborted
    // immediately
    if (stateOnUpdateClosed)
      return

    {
      GlobalHooks.invoke(GlobalHooks.transactionUpdateTaskBegin)
    }

    //-1 here indicate that transaction is started but is not finished yet
    if (ProducerTransaction.logger.isDebugEnabled) {
      ProducerTransaction.logger.debug("Update event for Transaction {}, partition: {}", transactionID, partition)
    }
    val transactionRecord = new RPCProducerTransaction(producer.stream.name, partition, transactionID, TransactionStates.Updated, -1, producer.producerOptions.transactionTtlMs)

    producer.stream.client.putTransaction(transactionRecord, true)(record => {
      doSendUpdateMessage()
    })

  }

  /**
    * accessor to lock object for external agents
    *
    * @return
    */
  def getTransactionLock(): ReentrantLock = transactionLock


  def getTransactionInfo(): ProducerCheckpointInfo = {

    val checkpoint = TransactionState(
      transactionID = getTransactionID(),
      ttlMs = -1,
      status = TransactionState.Status.Checkpointed,
      partition = partition,
      masterID = producer.getPartitionMasterIDLocalInfo(partition),
      orderID = -1,
      count = getDataItemsCount())

    ProducerCheckpointInfo(transactionRef = this,
      agent = producer.transactionOpenerService,
      checkpointEvent = checkpoint,
      streamName = producer.stream.name,
      partition = partition,
      transaction = getTransactionID(),
      totalCnt = getDataItemsCount(),
      ttl = producer.stream.ttl)
  }
}