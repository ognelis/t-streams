package com.bwsw.tstreams.velocity

import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

import akka.actor.ActorSystem
import com.aerospike.client.Host
import com.bwsw.tstreams.agents.consumer.{BasicConsumerOptions, SubscriberCoordinationOptions}
import com.bwsw.tstreams.agents.consumer.Offsets.Oldest
import com.bwsw.tstreams.agents.consumer.subscriber.{BasicSubscriberCallback, BasicSubscribingConsumer}
import com.bwsw.tstreams.agents.producer.InsertionType.BatchInsert
import com.bwsw.tstreams.agents.producer.{BasicProducer, BasicProducerOptions, ProducerCoordinationOptions, ProducerPolicies}
import com.bwsw.tstreams.converter.{ArrayByteToStringConverter, StringToArrayByteConverter}
import com.bwsw.tstreams.coordination.transactions.transport.impl.TcpTransport
import com.bwsw.tstreams.data.aerospike.{AerospikeStorageFactory, AerospikeStorageOptions}
import com.bwsw.tstreams.metadata.MetadataStorageFactory
import com.bwsw.tstreams.streams.BasicStream
import com.datastax.driver.core.Cluster



//  object ProducerRunner {
//    def main(args: Array[String]) {
//      import Common._
//      //producer/consumer options
//      val agentSettings = new ProducerCoordinationOptions(
//        agentAddress = "t-streams-2.z1.netpoint-dc.com:8888",
//        zkHosts = List(new InetSocketAddress("t-streams-1.z1.netpoint-dc.com", 2181)),
//        zkRootPath = "/com/bwsw/tstreams/velocity",
//        zkSessionTimeout = 7000,
//        isLowPriorityToBeMaster = true,
//        transport = new TcpTransport,
//        transportTimeout = 5,
//        zkConnectionTimeout = 7)
//
//      val producerOptions = new BasicProducerOptions[String, Array[Byte]](
//        transactionTTL = 6,
//        transactionKeepAliveInterval = 2,
//        producerKeepAliveInterval = 1,
//        RoundRobinPolicyCreator.getRoundRobinPolicy(stream, List(0)),
//        BatchInsert(10),
//        LocalGeneratorCreator.getGen(),
//        agentSettings,
//        stringToArrayByteConverter)
//
//      val producer = new BasicProducer[String, Array[Byte]]("producer", stream, producerOptions)
//      var cnt = 0
//      var timeNow = System.currentTimeMillis()
//      while (true) {
//        val txn = producer.newTransaction(ProducerPolicies.errorIfOpen)
//        0 until 10 foreach { x =>
//          txn.send(x.toString)
//        }
//        txn.checkpoint()
//        if (cnt % 1000 == 0) {
//          val time = System.currentTimeMillis()
//          val diff = time - timeNow
//          println(s"producer_time = $diff")
//          timeNow = time
//        }
//        cnt += 1
//      }
//    }
//  }
//
//  object SubscriberRunner {
//    def main(args: Array[String]) {
//      import Common._
//      val consumerOptions = new BasicConsumerOptions[Array[Byte], String](
//        transactionsPreload = 10,
//        dataPreload = 7,
//        consumerKeepAliveInterval = 5,
//        arrayByteToStringConverter,
//        RoundRobinPolicyCreator.getRoundRobinPolicy(stream, List(0)),
//        Oldest,
//        LocalGeneratorCreator.getGen(),
//        useLastOffset = true)
//
//      val lock = new ReentrantLock()
//      var cnt = 0
//      var timeNow = System.currentTimeMillis()
//      val callback = new BasicSubscriberCallback[Array[Byte], String] {
//        override def onEvent(subscriber: BasicSubscribingConsumer[Array[Byte], String], partition: Int, transactionUuid: UUID): Unit = {
//          lock.lock()
//          if (cnt % 1000 == 0){
//            val time = System.currentTimeMillis()
//            val diff = time - timeNow
//            println(s"subscriber_time = $diff")
//            timeNow = time
//          }
//          cnt += 1
//          lock.unlock()
//        }
//        override val pollingFrequency: Int = 100
//      }
//
//      val subscribeConsumer = new BasicSubscribingConsumer[Array[Byte], String](
//        name = "test_consumer",
//        stream = stream,
//        options = consumerOptions,
//        subscriberCoordinationOptions =
//          new SubscriberCoordinationOptions(agentAddress = "t-streams-4.z1.netpoint-dc.com:8588",
//            zkRootPath = "/com/bwsw/tstreams/velocity",
//            zkHosts = List(new InetSocketAddress("localhost", 2181)),
//            zkSessionTimeout = 7,
//            zkConnectionTimeout = 7),
//        callBack = callback,
//        persistentQueuePath = "Persistent queue path")
//      subscribeConsumer.start()
//    }
//  }
//
//  object MasterRunner {
//    import Common._
//    def main(args: Array[String]) {
//      //producer/consumer options
//      val agentSettings = new ProducerCoordinationOptions(
//        agentAddress = "t-streams-3.z1.netpoint-dc.com:8888",
//        zkHosts = List(new InetSocketAddress("t-streams-1.z1.netpoint-dc.com", 2181)),
//        zkRootPath = "/com/bwsw/tstreams/velocity",
//        zkSessionTimeout = 7000,
//        isLowPriorityToBeMaster = false,
//        transport = new TcpTransport,
//        transportTimeout = 5,
//        zkConnectionTimeout = 7)
//
//      val producerOptions = new BasicProducerOptions[String, Array[Byte]](
//        transactionTTL = 6,
//        transactionKeepAliveInterval = 2,
//        producerKeepAliveInterval = 1,
//        RoundRobinPolicyCreator.getRoundRobinPolicy(stream, List(0)),
//        BatchInsert(10),
//        LocalGeneratorCreator.getGen(),
//        agentSettings,
//        stringToArrayByteConverter)
//
//      new BasicProducer[String, Array[Byte]]("master", stream, producerOptions)
//    }
//  }

  object MetadataCreator {
    def main(args: Array[String]) {
      import Common._
      val cluster = Cluster.builder().addContactPoint("localhost").build()
      val session = cluster.connect()
      CassandraHelper.createKeyspace(session, keyspace)
      CassandraHelper.createMetadataTables(session, keyspace)
    }
  }
