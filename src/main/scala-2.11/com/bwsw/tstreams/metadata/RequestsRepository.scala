package com.bwsw.tstreams.metadata

import com.datastax.driver.core.{PreparedStatement, Session}

import scala.collection.mutable

/**
  * Created by Ivan Kudryavtsev on 24.09.16.
  */
object RequestsRepository {
  val requestsMap = mutable.Map[Session, RequestsStatements]()
  def getStatements(session: Session): RequestsStatements = this.synchronized {
    if (requestsMap.contains(session))
      requestsMap(session)
    else {
      val statements = RequestsStatements.prepare(session)
      requestsMap(session) = statements
      statements
    }
  }
}

object RequestsStatements {
  def prepare(session: Session): RequestsStatements = {

    val commitLog = "commit_log"
    val commitLogPutStatement = session.prepare(s"INSERT INTO $commitLog (stream, partition, interval, transaction, count) " +
      "VALUES (?, ?, ?, ?, ?) USING TTL ?")

    val scanStatement = s"SELECT stream, partition, interval, transaction, count, TTL(count) FROM $commitLog " +
      "WHERE stream = ? AND partition = ? AND interval = ?"

    val commitLogScanStatement = session.prepare(scanStatement)

    val commitLogGetStatement = session.prepare(s"$scanStatement AND transaction = ?")

    val commitLogDeleteStatement = session.prepare(s"DELETE FROM $commitLog WHERE stream = ? AND partition = ? AND interval = ? AND transaction = ?")

    RequestsStatements(
      commitLogPutStatement = commitLogPutStatement,
      commitLogGetStatement = commitLogGetStatement,
      commitLogScanStatement = commitLogScanStatement,
      commitLogDeleteStatement = commitLogDeleteStatement)
  }
}

case class RequestsStatements(commitLogPutStatement: PreparedStatement,
                              commitLogGetStatement: PreparedStatement,
                              commitLogDeleteStatement: PreparedStatement,
                              commitLogScanStatement: PreparedStatement)