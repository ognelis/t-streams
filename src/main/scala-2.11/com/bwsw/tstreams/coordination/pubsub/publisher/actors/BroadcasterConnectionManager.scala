package com.bwsw.tstreams.coordination.pubsub.publisher.actors

import akka.actor.{ActorRef, ActorSystem, Props}
import com.bwsw.tstreams.coordination.pubsub.publisher.actors.BroadcasterConnectionActor.{ChannelInactiveCommand, UpdateSubscribersCommand}
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelId

class BroadcasterConnectionManager(system : ActorSystem, bootstrap : Bootstrap) {
  private val handler: ActorRef = system.actorOf(
    props = Props(new BroadcasterConnectionActor(bootstrap)))

  def updateSubscribers(newSubscribers : List[String]) = {
    handler ! UpdateSubscribersCommand(newSubscribers)
  }

  def channelInactive(id : ChannelId) = {
    handler ! ChannelInactiveCommand(id)
  }
}
