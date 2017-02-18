package agents.integration

/**
  * Created by mendelbaum_ma on 08.09.16.
  */

import java.util.concurrent.{CountDownLatch, TimeUnit}

import com.bwsw.tstreams.agents.consumer.Offset.Newest
import com.bwsw.tstreams.agents.consumer.subscriber.Callback
import com.bwsw.tstreams.agents.consumer.{ConsumerTransaction, TransactionOperator}
import com.bwsw.tstreams.agents.producer.NewTransactionProducerPolicy
import com.bwsw.tstreams.env.ConfigurationOptions
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import testutils.{LocalGeneratorCreator, TestUtils}

import scala.collection.mutable.ListBuffer


class SubscriberWithTwoProducersFirstCancelSecondCheckpointTest extends FlatSpec with Matchers with BeforeAndAfterAll with TestUtils {
  f.setProperty(ConfigurationOptions.Stream.name, "test_stream").
    setProperty(ConfigurationOptions.Stream.partitionsCount, 3).
    setProperty(ConfigurationOptions.Stream.ttlSec, 60 * 10).
    setProperty(ConfigurationOptions.Coordination.connectionTimeoutMs, 7).
    setProperty(ConfigurationOptions.Coordination.sessionTimeoutMs, 7).
    setProperty(ConfigurationOptions.Producer.transportTimeoutMs, 5).
    setProperty(ConfigurationOptions.Producer.Transaction.ttlMs, 3).
    setProperty(ConfigurationOptions.Producer.Transaction.keepAliveMs, 1).
    setProperty(ConfigurationOptions.Consumer.transactionPreload, 10).
    setProperty(ConfigurationOptions.Consumer.dataPreload, 10)
  it should "Integration MixIn checkpoint and cancel must be correctly processed on Subscriber " in {

    val bp1 = ListBuffer[Long]()
    val bp2 = ListBuffer[Long]()
    val bs = ListBuffer[Long]()

    val lp2 = new CountDownLatch(1)
    val ls = new CountDownLatch(1)

    val subscriber = f.getSubscriber[String](name = "ss+2",
      transactionGenerator = LocalGeneratorCreator.getGen(),
      converter = arrayByteToStringConverter,
      partitions = Set(0),
      offset = Newest,
      useLastOffset = true,
      callback = new Callback[String] {
        override def onTransaction(consumer: TransactionOperator[String], transaction: ConsumerTransaction[String]): Unit = this.synchronized {
          bs.append(transaction.getTransactionID())
          ls.countDown()
        }
      })

    subscriber.start()

    val producer1 = f.getProducer[String](
      name = "test_producer1",
      transactionGenerator = LocalGeneratorCreator.getGen(),
      converter = stringToArrayByteConverter,
      partitions = Set(0))

    val producer2 = f.getProducer[String](
      name = "test_producer2",
      transactionGenerator = LocalGeneratorCreator.getGen(),
      converter = stringToArrayByteConverter,
      partitions = Set(0))



    val t1 = new Thread(new Runnable {
      override def run(): Unit = {
        val transaction = producer1.newTransaction(policy = NewTransactionProducerPolicy.CheckpointIfOpened)
        lp2.countDown()
        bp1.append(transaction.getTransactionID())
        transaction.send("test")
        transaction.cancel()
      }
    })

    val t2 = new Thread(new Runnable {
      override def run(): Unit = {
        lp2.await()
        val t = producer2.newTransaction(policy = NewTransactionProducerPolicy.CheckpointIfOpened)
        bp2.append(t.getTransactionID())
        t.send("test")
        t.checkpoint()
      }
    })

    //subscriber.start()

    t1.start()
    t2.start()

    t1.join()
    t2.join()

    ls.await(10, TimeUnit.SECONDS)

    producer1.stop()
    producer2.stop()

    subscriber.stop()

    bs.size shouldBe 1 // Adopted by only one and it is from second
    bp2.head shouldBe bs.head
  }

  override def afterAll(): Unit = {
    onAfterAll()
  }
}