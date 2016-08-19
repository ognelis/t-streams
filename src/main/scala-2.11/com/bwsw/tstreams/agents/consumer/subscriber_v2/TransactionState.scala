package com.bwsw.tstreams.agents.consumer.subscriber_v2

import java.util.UUID

import com.bwsw.tstreams.coordination.messages.state.TransactionStatus.ProducerTransactionStatus

/**
  * Created by ivan on 19.08.16.
  */
case class TransactionState(uuid: UUID,
                       masterSessionID: Int,
                       queueOrderID: Int,
                       itemCount: Int,
                       state: ProducerTransactionStatus,
                       ttl: Int)
