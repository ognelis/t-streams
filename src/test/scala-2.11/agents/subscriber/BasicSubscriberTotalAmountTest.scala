package agents.subscriber

import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.locks.ReentrantLock
import java.util.UUID
import com.aerospike.client.Host
import com.bwsw.tstreams.agents.consumer.{BasicConsumerCallback, BasicConsumerWithSubscribe, BasicConsumerOptions}
import com.bwsw.tstreams.agents.consumer.Offsets.Oldest
import com.bwsw.tstreams.agents.producer.{ProducerPolicies, BasicProducer, BasicProducerOptions}
import com.bwsw.tstreams.agents.producer.InsertionType.BatchInsert
import com.bwsw.tstreams.converter.{StringToArrayByteConverter, ArrayByteToStringConverter}
import com.bwsw.tstreams.coordination.Coordinator
import com.bwsw.tstreams.data.aerospike.{AerospikeStorageOptions, AerospikeStorageFactory}
import com.bwsw.tstreams.metadata.MetadataStorageFactory
import com.bwsw.tstreams.streams.BasicStream
import com.datastax.driver.core.Cluster
import org.redisson.{Redisson, Config}
import org.scalatest.{BeforeAndAfterAll, Matchers, FlatSpec}
import testutils.{LocalGeneratorCreator, RoundRobinPolicyCreator, CassandraHelper, RandomStringCreator}


class BasicSubscriberTotalAmountTest extends FlatSpec with Matchers with BeforeAndAfterAll{
  //creating keyspace, metadata
  def randomString: String = RandomStringCreator.randomAlphaString(10)
  val randomKeyspace = randomString
  val cluster = Cluster.builder().addContactPoint("localhost").build()
  val session = cluster.connect()
  CassandraHelper.createKeyspace(session, randomKeyspace)
  CassandraHelper.createMetadataTables(session, randomKeyspace)

  //metadata/data factories
  val metadataStorageFactory = new MetadataStorageFactory
  val storageFactory = new AerospikeStorageFactory

  //converters to convert usertype->storagetype; storagetype->usertype
  val arrayByteToStringConverter = new ArrayByteToStringConverter
  val stringToArrayByteConverter = new StringToArrayByteConverter

  //aerospike storage instances
  val hosts = List(
    new Host("localhost",3000),
    new Host("localhost",3001),
    new Host("localhost",3002),
    new Host("localhost",3003))
  val aerospikeOptions = new AerospikeStorageOptions("test", hosts)
  val aerospikeInstForProducer = storageFactory.getInstance(aerospikeOptions)
  val aerospikeInstForConsumer = storageFactory.getInstance(aerospikeOptions)

  //metadata storage instances
  val metadataStorageInstForProducer = metadataStorageFactory.getInstance(
    cassandraHosts = List(new InetSocketAddress("localhost", 9042)),
    keyspace = randomKeyspace)
  val metadataStorageInstForConsumer = metadataStorageFactory.getInstance(
    cassandraHosts = List(new InetSocketAddress("localhost", 9042)),
    keyspace = randomKeyspace)

  //coordinator for coordinating producer/consumer
  val config = new Config()
  config.useSingleServer().setAddress("localhost:6379")
  val redissonClient = Redisson.create(config)
  val coordinator = new Coordinator("some_path", redissonClient)

  //stream instances for producer/consumer
  val streamForProducer: BasicStream[Array[Byte]] = new BasicStream[Array[Byte]](
    name = "test_stream",
    partitions = 3,
    metadataStorage = metadataStorageInstForProducer,
    dataStorage = aerospikeInstForProducer,
    coordinator = coordinator,
    ttl = 60 * 10,
    description = "some_description")

  val streamForConsumer = new BasicStream[Array[Byte]](
    name = "test_stream",
    partitions = 3,
    metadataStorage = metadataStorageInstForConsumer,
    dataStorage = aerospikeInstForConsumer,
    coordinator = coordinator,
    ttl = 60 * 10,
    description = "some_description")

  //producer/consumer options
  val producerOptions = new BasicProducerOptions[String, Array[Byte]](
    transactionTTL = 6,
    transactionKeepAliveInterval = 2,
    producerKeepAliveInterval = 1,
    RoundRobinPolicyCreator.getRoundRobinPolicy(streamForProducer, List(0,1,2)),
    BatchInsert(5),
    LocalGeneratorCreator.getGen(),
    stringToArrayByteConverter)

  val consumerOptions = new BasicConsumerOptions[Array[Byte], String](
    transactionsPreload = 10,
    dataPreload = 7,
    consumerKeepAliveInterval = 5,
    arrayByteToStringConverter,
    RoundRobinPolicyCreator.getRoundRobinPolicy(streamForConsumer, List(0,1,2)),
    Oldest,
    LocalGeneratorCreator.getGen(),
    useLastOffset = true)


  val lock = new ReentrantLock()
  var acc = 0
  val producer = new BasicProducer("test_producer", streamForProducer, producerOptions)
  val callback = new BasicConsumerCallback[Array[Byte], String] {
    override def onEvent(subscriber : BasicConsumerWithSubscribe[Array[Byte], String], partition: Int, transactionUuid: UUID): Unit = {
      lock.lock()
      acc += 1
      lock.unlock()
    }
    override val frequency: Int = 1
  }
  val path = randomString
  val subscribeConsumer = new BasicConsumerWithSubscribe[Array[Byte],String]("test_consumer", streamForConsumer, consumerOptions, callback, path)

  "subscribe consumer" should "retrieve all sent messages" in {
    val totalMsg = 30
    val dataInTxn = 10
    val data = randomString

    subscribeConsumer.start()

    (0 until totalMsg) foreach { x=>
      val txn = producer.newTransaction(ProducerPolicies.errorIfOpen)
      (0 until dataInTxn) foreach { _ =>
        txn.send(data)
      }
      txn.close()
    }
    Thread.sleep(10000)

    subscribeConsumer.close()

    acc shouldEqual totalMsg
  }

  override def afterAll(): Unit = {
    session.execute(s"DROP KEYSPACE $randomKeyspace")
    session.close()
    cluster.close()
    metadataStorageFactory.closeFactory()
    storageFactory.closeFactory()
    redissonClient.shutdown()
    val file = new File(path)
    remove(file)
  }

  def remove(f : File) : Unit = {
    if (f.isDirectory) {
      for (c <- f.listFiles())
      remove(c)
    }
    f.delete()
  }
}