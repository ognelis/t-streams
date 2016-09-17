package agents.integration


import com.bwsw.tstreams.agents.consumer.Offset.Oldest
import com.bwsw.tstreams.agents.producer.NewTransactionProducerPolicy
import com.bwsw.tstreams.entities.CommitEntity
import com.bwsw.tstreams.env.TSF_Dictionary
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import testutils._


class ConsumerTest extends FlatSpec with Matchers with BeforeAndAfterAll with TestUtils {
  f.setProperty(TSF_Dictionary.Stream.NAME, "test_stream").
    setProperty(TSF_Dictionary.Stream.PARTITIONS, 3).
    setProperty(TSF_Dictionary.Stream.TTL, 60 * 10).
    setProperty(TSF_Dictionary.Coordination.CONNECTION_TIMEOUT, 7).
    setProperty(TSF_Dictionary.Coordination.TTL, 7).
    setProperty(TSF_Dictionary.Producer.TRANSPORT_TIMEOUT, 5).
    setProperty(TSF_Dictionary.Producer.Transaction.TTL, 6).
    setProperty(TSF_Dictionary.Producer.Transaction.KEEP_ALIVE, 2).
    setProperty(TSF_Dictionary.Consumer.TRANSACTION_PRELOAD, 10).
    setProperty(TSF_Dictionary.Consumer.DATA_PRELOAD, 10)

  val gen = LocalGeneratorCreator.getGen()

  val consumer = f.getConsumer[String](
    name = "test_consumer",
    transactionGenerator = gen,
    converter = arrayByteToStringConverter,
    partitions = Set(0, 1, 2),
    offset = Oldest,
    isUseLastOffset = true)

  val producer = f.getProducer[String](
    name = "test_producer",
    transactionGenerator = gen,
    converter = stringToArrayByteConverter,
    partitions = Set(0, 1, 2),
    isLowPriority = false)

  "consumer.getTransaction" should "return None if nothing was sent" in {
    consumer.start
    val transaction = consumer.getTransaction(0)
    transaction.isEmpty shouldBe true
  }

  "consumer.getTransactionById" should "return sent transaction" in {
    val totalDataInTransaction = 10
    val data = (for (i <- 0 until totalDataInTransaction) yield randomString).toList.sorted
    val transaction = producer.newTransaction(NewTransactionProducerPolicy.ErrorIfOpened, 1)
    val transactionID = transaction.getTransactionID
    data.foreach(x => transaction.send(x))
    transaction.checkpoint()
    var checkVal = true

    val consumedTransaction = consumer.getTransactionById(1, transactionID).get
    checkVal = consumedTransaction.getPartition == transaction.getPartition
    checkVal = consumedTransaction.getTransactionID == transactionID
    checkVal = consumedTransaction.getAll().sorted == data

    checkVal shouldEqual true
  }

  "consumer.getTransaction" should "return sent transaction" in {
    val transaction = consumer.getTransaction(1)
    transaction.isDefined shouldEqual true
  }

  "consumer.getLastTransaction" should "return last closed transaction" in {
    val commitEntity = new CommitEntity("commit_log", cluster.connect(randomKeyspace))
    val transactions = for (i <- 0 until 100) yield LocalGeneratorCreator.getTransaction()
    val transaction = transactions.head
    commitEntity.commit("test_stream", 1, transactions.head, 1, 120)
    transactions.drop(1) foreach { t =>
      commitEntity.commit("test_stream", 1, t, -1, 120)
    }
    val retrievedTransaction = consumer.getLastTransaction(partition = 1).get
    retrievedTransaction.getTransactionID shouldEqual transaction
  }

  "consumer.getTransactionsFromTo" should "return all transactions if no incomplete" in {
    val commitEntity = new CommitEntity("commit_log", cluster.connect(randomKeyspace))
    val ALL = 100
    val transactions = for (i <- 0 until ALL) yield LocalGeneratorCreator.getTransaction()
    val firstTransaction = transactions.head
    val lastTransaction = transactions.last
    transactions foreach { x =>
      commitEntity.commit("test_stream", 1, x, 1, 120)
    }
    val res = consumer.getTransactionsFromTo(1, firstTransaction, lastTransaction)
    res.size shouldBe transactions.drop(1).size
  }

  "consumer.getTransactionsFromTo" should "return only transactions up to 1st incomplete" in {
    val commitEntity = new CommitEntity("commit_log", cluster.connect(randomKeyspace))
    val FIRST = 30
    val LAST = 100
    val transactions1 = for (i <- 0 until FIRST) yield LocalGeneratorCreator.getTransaction()
    transactions1 foreach { x =>
      commitEntity.commit("test_stream", 1, x, 1, 120)
    }
    commitEntity.commit("test_stream", 1, LocalGeneratorCreator.getTransaction(), -1, 120)
    val transactions2 = for (i <- FIRST until LAST) yield LocalGeneratorCreator.getTransaction()
    transactions2 foreach { x =>
      commitEntity.commit("test_stream", 1, x, 1, 120)
    }
    val transactions = transactions1 ++ transactions2
    val firstTransaction = transactions.head
    val lastTransaction = transactions.last


    val res = consumer.getTransactionsFromTo(1, firstTransaction, lastTransaction)
    res.size shouldBe transactions1.drop(1).size
  }

  "consumer.getTransactionsFromTo" should "return none if empty" in {
    val commitEntity = new CommitEntity("commit_log", cluster.connect(randomKeyspace))
    val ALL = 100
    val transactions = for (i <- 0 until ALL) yield LocalGeneratorCreator.getTransaction()
    val firstTransaction = transactions.head
    val lastTransaction = transactions.last
    val res = consumer.getTransactionsFromTo(1, firstTransaction, lastTransaction)
    res.size shouldBe 0
  }

  "consumer.getTransactionsFromTo" should "return none if to < from" in {
    val commitEntity = new CommitEntity("commit_log", cluster.connect(randomKeyspace))
    val ALL = 100
    val transactions = for (i <- 0 until ALL) yield LocalGeneratorCreator.getTransaction()
    val firstTransaction = transactions.head
    val lastTransaction = transactions.tail.tail.tail.head
    transactions foreach { x =>
      commitEntity.commit("test_stream", 1, x, 1, 120)
    }
    val res = consumer.getTransactionsFromTo(1, lastTransaction, firstTransaction)
    res.size shouldBe 0
  }


  override def afterAll(): Unit = {
    producer.stop()
    onAfterAll()
  }
}