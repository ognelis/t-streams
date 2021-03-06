package com.bwsw.tstreams.testutils

import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger

import com.bwsw.tstreams.debug.GlobalHooks
import com.bwsw.tstreams.env.{ConfigurationOptions, TStreamsFactory}
import com.google.common.io.Files
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer

/**
  * Test help utils
  */
trait TestUtils {
  protected val batchSizeTestVal = 5

  /**
    * Random alpha string generator
    *
    * @return Alpha string
    */
  val id = TestUtils.moveId()
  val randomKeyspace = TestUtils.getKeyspace(id)
  val coordinationRoot = s"/$randomKeyspace"

  val zookeeperPort = TestUtils.ZOOKEEPER_PORT


  val logger = LoggerFactory.getLogger(this.getClass)
  val uptime = ManagementFactory.getRuntimeMXBean.getStartTime

  logger.info("-------------------------------------------------------")
  logger.info("Test suite " + this.getClass.toString + " started")
  logger.info("Test Suite uptime is " + ((System.currentTimeMillis - uptime) / 1000L).toString + " seconds")
  logger.info("-------------------------------------------------------")


  val f = new TStreamsFactory()
  f.setProperty(ConfigurationOptions.Coordination.prefix, coordinationRoot)
    .setProperty(ConfigurationOptions.Coordination.endpoints, s"localhost:$zookeeperPort")
    .setProperty(ConfigurationOptions.StorageClient.Zookeeper.endpoints, s"localhost:$zookeeperPort")
    .setProperty(ConfigurationOptions.Stream.name, "test-stream")

  val curatorClient = CuratorFrameworkFactory.builder()
    .namespace("")
    .connectionTimeoutMs(7000)
    .sessionTimeoutMs(7000)
    .retryPolicy(new ExponentialBackoffRetry(1000, 3))
    .connectString(s"127.0.0.1:$zookeeperPort").build()
  curatorClient.start()

  if (curatorClient.checkExists().forPath("/tts") == null)
    curatorClient.create().forPath("/tts")

  removeZkMetadata(f.getProperty(ConfigurationOptions.Coordination.prefix).toString)

  def getRandomString: String = RandomStringCreator.randomAlphaString(10)

  /**
    * Sorting checker
    */
  def isSorted(list: ListBuffer[Long]): Boolean = {
    if (list.isEmpty)
      return true
    var checkVal = true
    var curVal = list.head
    list foreach { el =>
      if (el < curVal)
        checkVal = false
      if (el > curVal)
        curVal = el
    }
    checkVal
  }

  /**
    * Remove zk metadata from concrete root
    *
    * @param path Zk root to delete
    */
  def removeZkMetadata(path: String) = {
    if (curatorClient.checkExists.forPath(path) != null)
      curatorClient.delete.deletingChildrenIfNeeded().forPath(path)
  }

  /**
    * Remove directory recursive
    *
    * @param f Dir to remove
    */
  def remove(f: File): Unit = {
    if (f.isDirectory) {
      for (c <- f.listFiles())
        remove(c)
    }
    f.delete()
  }

  def onAfterAll() = {
    System.setProperty("DEBUG", "false")
    GlobalHooks.addHook(GlobalHooks.preCommitFailure, () => ())
    GlobalHooks.addHook(GlobalHooks.afterCommitFailure, () => ())
    removeZkMetadata(f.getProperty(ConfigurationOptions.Coordination.prefix).toString)
    removeZkMetadata("/unit")
    curatorClient.close()
    f.dumpStorageClients()
  }
}

object TestUtils {
  System.getProperty("java.io.tmpdir", "./target/")
  val ZOOKEEPER_PORT = 21810

  private val id: AtomicInteger = new AtomicInteger(0)

  def moveId(): Int = {
    val rid = id.incrementAndGet()
    rid
  }

  def getKeyspace(id: Int): String = "tk_" + id.toString

  def getTmpDir(): String = Files.createTempDir().toString

  private val zk = new ZookeeperTestServer(ZOOKEEPER_PORT, Files.createTempDir().toString)

}

