package com.bwsw.tstreams.agents.consumer.subscriber

import com.bwsw.tstreams.agents.consumer.{Transaction, TransactionOperator}

/**
  * Trait to implement to handle incoming messages
  */
trait Callback[T] {
  /**
    * Callback which is called on every closed transaction
    *
    * @param consumer        associated Consumer
    * @param transaction
    */
  def onEvent(consumer: TransactionOperator[T],
              transaction: Transaction[T]): Unit

  /**
    *
    * @param consumer
    * @param partition
    * @param uuid
    * @param count
    */
  final def onEventCall(consumer: TransactionOperator[T],
                  partition: Int,
                  uuid: java.util.UUID,
                  count: Int) = {
    val txnOpt = consumer.buildTransactionObject(partition, uuid, count)
    onEvent(consumer, transaction = txnOpt.get)
  }
}
