package com.bwsw.tstreams.env.defaults

import com.bwsw.tstreams.env.ConfigurationOptions

import scala.collection.mutable

/**
  * Created by ivan on 22.04.17.
  */
object TStreamsFactoryCommonDefaults {

  object Common {
    val authenticationToken = ""
  }

  def get = {
    val m = mutable.HashMap[String, Any]()
    val co = ConfigurationOptions.Common
    m(co.authenticationToken) = Common.authenticationToken
    m
  }
}
