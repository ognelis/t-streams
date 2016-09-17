package entities

import com.bwsw.tstreams.common.CassandraHelper
import com.bwsw.tstreams.entities.CommitEntity
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import testutils.{RandomStringCreator, TestUtils}


class RefreshEntityTest extends FlatSpec with Matchers with BeforeAndAfterAll with TestUtils {
  def randomVal: String = RandomStringCreator.randomAlphaString(10)

  val connectedSession = cluster.connect(randomKeyspace)

  "After dropping metadata tables and creating them again commit entity" should "work" in {
    val commitEntity = new CommitEntity("commit_log", connectedSession)
    val stream = randomVal
    val transaction = System.currentTimeMillis()
    val partition = 10
    val totalCnt = 123
    val ttl = 3

    commitEntity.commit(stream, partition, transaction, totalCnt, ttl)
    CassandraHelper.clearMetadataTables(session, randomKeyspace)
    commitEntity.commit(stream, partition, transaction, totalCnt, ttl)
  }
}
