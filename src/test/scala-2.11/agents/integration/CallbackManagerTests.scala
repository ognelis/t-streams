package agents.integration

import com.bwsw.tstreams.coordination.messages.state.{TransactionStateMessage, TransactionStatus}
import com.bwsw.tstreams.coordination.subscriber.CallbackManager
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FlatSpec, Matchers}
import testutils.LocalGeneratorCreator

/**
  * Created by Ivan Kudryavtsev on 06.08.16.
  */
class CallbackManagerTests extends FlatSpec with Matchers with MockFactory {
  val cm = new CallbackManager


  "Ensure initialization" should "be correct" in {
    cm.getCount() shouldBe 0
  }

  "Ensure callback" should "added" in {
    val m = TransactionStateMessage(transactionID = LocalGeneratorCreator.getTransaction(), ttl = 10, status = TransactionStatus.update, partition = 1, 1, 0, 0)
    var cnt = 0
    val mf = (m: TransactionStateMessage) => {
      cnt += 1
    }
    cm.addCallback(mf)
    cm.invokeCallbacks(m)
    cnt shouldBe 1
  }
}
