package com.bwsw.tstreams.agents.subscriber

import com.bwsw.tstreams.agents.consumer.subscriber.QueueBuilder
import com.bwsw.tstreams.common.InMemoryQueue
import com.bwsw.tstreams.proto.protocol.TransactionState
import org.scalatest.{FlatSpec, Matchers}

/**
  * Created by Ivan Kudryavtsev on 19.08.16.
  */
class QueueBuilderTests extends FlatSpec with Matchers {
  it should "Return InMemoryQueue" in {
    new QueueBuilder.InMemory()
      .generateQueueObject(0)
      .isInstanceOf[InMemoryQueue[List[TransactionState]]] shouldBe true
  }

}
