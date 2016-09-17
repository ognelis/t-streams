package com.bwsw.tstreams.agents.consumer.subscriber

import com.bwsw.tstreams.coordination.messages.state.TransactionStatus.ProducerTransactionStatus

/**
  * Created by Ivan Kudryavtsev on 19.08.16.
  */
case class TransactionState(transactionID: Long,
                            partition: Int,
                            var masterSessionID: Int,
                            var queueOrderID: Long,
                            var itemCount: Int,
                            var state: ProducerTransactionStatus,
                            var ttl: Long) {
  override def toString() = {
    s"TransactionState($transactionID, $partition, $masterSessionID, $queueOrderID, $itemCount, $state, $ttl)"
  }
}
