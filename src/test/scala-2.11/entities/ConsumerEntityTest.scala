package entities


import com.bwsw.tstreams.entities.ConsumerEntity
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import testutils.{LocalGeneratorCreator, RandomStringCreator, TestUtils}


class ConsumerEntityTest extends FlatSpec with Matchers with BeforeAndAfterAll with TestUtils {
  def randomVal: String = RandomStringCreator.randomAlphaString(10)

  val connectedSession = cluster.connect(randomKeyspace)

  "ConsumerEntity.saveSingleOffset() ConsumerEntity.exist() ConsumerEntity.getOffset()" should "create new consumer with particular offset," +
    " then check consumer existence, then get this consumer offset" in {
    val consumerEntity = new ConsumerEntity("consumers", connectedSession)
    val consumer = randomVal
    val stream = randomVal
    val partition = 1
    val offset = LocalGeneratorCreator.getTransaction()
    consumerEntity.saveSingleOffset(consumer, stream, partition, offset)
    val checkExist: Boolean = consumerEntity.exists(consumer)
    val retValOffset = consumerEntity.getLastSavedOffset(consumer, stream, partition)

    val checkVal = checkExist && retValOffset == offset
    checkVal shouldBe true
  }

  "ConsumerEntity.exist()" should "return false if consumer not exist" in {
    val consumerEntity = new ConsumerEntity("consumers", connectedSession)
    val consumer = randomVal
    consumerEntity.exists(consumer) shouldEqual false
  }

  "ConsumerEntity.getOffset()" should "throw java.lang.IndexOutOfBoundsException if consumer not exist" in {
    val consumerEntity = new ConsumerEntity("consumers", connectedSession)
    val consumer = randomVal
    val stream = randomVal
    val partition = 1
    intercept[java.lang.IndexOutOfBoundsException] {
      consumerEntity.getLastSavedOffset(consumer, stream, partition)
    }
  }

  "ConsumerEntity.saveBatchOffset(); ConsumerEntity.getOffset()" should "create new consumer with particular offsets and " +
    "then validate this consumer offsets" in {
    val consumerEntity = new ConsumerEntity("consumers", connectedSession)
    val consumer = randomVal
    val stream = randomVal
    val offsets = scala.collection.mutable.Map[Int, Long]()
    for (i <- 0 to 100)
      offsets(i) = LocalGeneratorCreator.getTransaction()

    consumerEntity.saveBatchOffset(consumer, stream, offsets)

    var checkVal = true

    for (i <- 0 to 100) {
      val id = consumerEntity.getLastSavedOffset(consumer, stream, i)
      checkVal &= id == offsets(i)
    }
    checkVal shouldBe true
  }

  override def afterAll(): Unit = {
    onAfterAll()
  }
}
