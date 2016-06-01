package com.bwsw.tstreams.coordination.pubsub.listener

import java.util.concurrent.CountDownLatch
import com.bwsw.tstreams.coordination.pubsub.messages.ProducerTopicMessage
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.{Delimiters, DelimiterBasedFrameDecoder}
import io.netty.handler.logging.{LogLevel, LoggingHandler}

/**
 * Listener of [[ProducerTopicMessage]]
 * @param port Listener port
 */
class ProducerTopicMessageListener(port : Int) {
  private val bossGroup = new NioEventLoopGroup(1)
  private val workerGroup = new NioEventLoopGroup()
  private val MAX_FRAME_LENGTH = 8192
  private val channelHandler : SubscriberChannelHandler = new SubscriberChannelHandler
  private var listenerThread : Thread = null

  /**
   * Stop to listen [[ProducerTopicMessage]]]
   */
  def stop() : Unit = {
    channelHandler.stopCallback()
    workerGroup.shutdownGracefully().await()
    bossGroup.shutdownGracefully().await()
  }

  /**
   * Add new event to [[channelHandler]]]
   * @param callback Event callback
   */
  def addCallbackToChannelHandler(callback : (ProducerTopicMessage) => Unit) : Unit = {
    channelHandler.addCallback(callback)
  }

  /**
   * Retrieve count of accepted
   * connection managed by [[channelHandler]]]
   */
  def getConnectionsAmount() : Int = {
    channelHandler.getCount()
  }

  /**
   * Start invoking callbacks on new [[ProducerTopicMessage]]]
   */
  def startCallback() : Unit = {
    channelHandler.startCallBack()
  }

  /**
   * Start this listener
   */
  def start() : Unit = {
    assert(listenerThread == null || !listenerThread.isAlive)
    val syncPoint = new CountDownLatch(1)
    listenerThread = new Thread(new Runnable {
      override def run(): Unit = {
        try {
          val b = new ServerBootstrap()
          b.group(bossGroup, workerGroup).channel(classOf[NioServerSocketChannel])
            .handler(new LoggingHandler(LogLevel.DEBUG))
            .childHandler(new ChannelInitializer[SocketChannel]() {
              override def initChannel(ch: SocketChannel) {
                val p = ch.pipeline()
                p.addLast("framer", new DelimiterBasedFrameDecoder(MAX_FRAME_LENGTH, Delimiters.lineDelimiter():_*))
                p.addLast("decoder", new StringDecoder())
                p.addLast("deserializer", new ProducerTopicMessageDecoder())
                p.addLast("handler", channelHandler)
              }
            })
          val f = b.bind(port).sync()
          syncPoint.countDown()
          f.channel().closeFuture().sync()
        } finally {
          workerGroup.shutdownGracefully()
          bossGroup.shutdownGracefully()
        }
      }
    })
    listenerThread.start()
    syncPoint.await()
  }
}