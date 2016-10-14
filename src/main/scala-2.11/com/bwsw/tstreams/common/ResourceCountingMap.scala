package com.bwsw.tstreams.common

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable

/**
  * Created by Ivan Kudryavtsev on 14.10.16.
  */
class ResourceCountingMap[K, V, T](deleteCallback: (V) => T) {
  private val map = mutable.Map[K, (AtomicInteger, V)]()

  def acquire(key: K) = map.synchronized {
    val valueOpt = map.get(key)
    valueOpt.foreach(valueEntry => valueEntry._1.incrementAndGet())
    valueOpt
  }

  def release(key: K) = map.synchronized {
    val valueOpt = map.get(key)
    valueOpt.foreach(valueEntry => {
      if(valueEntry._1.decrementAndGet() == 0) deleteCallback(valueEntry._2)
      map.remove(key)
    })
    valueOpt
  }

  def place(key: K, value: V) = map.synchronized {
    map(key) = (new AtomicInteger(0), value)
  }

}
