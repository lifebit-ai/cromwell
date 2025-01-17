package cromwell.services.metadata.impl

import java.time.OffsetDateTime

import akka.actor.ActorRef
import akka.pattern._
import akka.testkit.TestProbe
import com.typesafe.config.{Config, ConfigFactory}
import cromwell.core._
import cromwell.services.metadata.MetadataService._
import cromwell.services.metadata._
import cromwell.services.metadata.impl.MetadataServiceActorSpec._
import cromwell.services.{ServicesSpec, SuccessfulMetadataJsonResponse}
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import spray.json._

import scala.concurrent.Await
import scala.concurrent.duration._

class MetadataServiceActorSpec extends ServicesSpec {
  import MetadataServiceActorSpec.Config

  def actorName: String = "MetadataServiceActor"

  val config: Config = ConfigFactory.parseString(Config)
  lazy val actor: ActorRef =
    system.actorOf(
      props =
        MetadataServiceActor.props(
          serviceConfig = globalConfigToMetadataServiceConfig(config),
          globalConfig = config,
          serviceRegistryActor = TestProbe("serviceRegistryActor").ref,
        ),
      name = "MetadataServiceActor-for-MetadataServiceActorSpec",
    )

  val workflowId: WorkflowId = WorkflowId.randomId()

    /*
    Simple store / retrieve
     */

  val key1: MetadataKey = MetadataKey(workflowId, None, "key1")
  val key2: MetadataKey = MetadataKey(workflowId, None, "key2")
  val supJob: MetadataJobKey = MetadataJobKey("sup.sup", None, 1)
  val key3: MetadataKey = MetadataKey(workflowId, Option(supJob), "dog")
  val moment: OffsetDateTime = OffsetDateTime.now.minusMinutes(1)

  val event1_1: MetadataEvent = MetadataEvent(key1, Option(MetadataValue("value1")), moment.plusSeconds(1))
  val event1_2: MetadataEvent = MetadataEvent(key1, Option(MetadataValue("value2")), moment.plusSeconds(2))
  val event2_1: MetadataEvent = MetadataEvent(key2, Option(MetadataValue("value1")), moment.plusSeconds(3))
  val event3_1: MetadataEvent = MetadataEvent(key3, Option(MetadataValue("value3")), moment.plusSeconds(4))
  val event3_2: MetadataEvent = MetadataEvent(key3, None, moment.plusSeconds(5))

  override def beforeAll: Unit = {

    // Even though event1_1 arrives second, the older timestamp should mean it does not replace event1_2:
    val putAction2 = PutMetadataAction(event1_2)
    val putAction1 = PutMetadataAction(event1_1)
    val putAction3 = PutMetadataAction(event2_1, event3_1, event3_2)

    actor ! putAction1
    actor ! putAction2
    actor ! putAction3
  }

  val query1: MetadataQuery = MetadataQuery.forKey(key1)
  val query2: MetadataQuery = MetadataQuery.forKey(key2)
  val query3: MetadataQuery = MetadataQuery.forKey(key3)
  val query4: MetadataQuery = MetadataQuery.forWorkflow(workflowId)
  val query5: MetadataQuery = MetadataQuery.forJob(workflowId, supJob)

  val testCases: List[(String, MetadataQuery, String)] = List[(String, MetadataQuery, String)] (
    ("query1", query1, s"""{
                          |  "key1": "value2",
                          |  "calls": {},
                          |  "id": "$workflowId"
                          |}""".stripMargin),
    ("query2", query2, s"""{
                          |  "key2": "value1",
                          |  "calls": {},
                          |  "id": "$workflowId"
                          |}""".stripMargin),
    ("query3", query3, s"""{
                          |  "calls": {
                          |    "sup.sup": [{
                          |      "dog": {},
                          |      "attempt": 1,
                          |      "shardIndex": -1
                          |    }]
                          |  },
                          |  "id": "$workflowId"
                          |}""".stripMargin),
    ("query4", query4, s"""{
                          |  "key1": "value2",
                          |  "key2": "value1",
                          |  "calls": {
                          |    "sup.sup": [{
                          |      "dog": {},
                          |      "attempt": 1,
                          |      "shardIndex": -1
                          |    }]
                          |  },
                          |  "id": "$workflowId"
                          |}""".stripMargin),
    ("query5", query5, s"""{
                          |  "calls": {
                          |    "sup.sup": [{
                          |      "dog": {},
                          |      "attempt": 1,
                          |      "shardIndex": -1
                          |    }]
                          |  },
                          |  "id": "$workflowId"
                          |}""".stripMargin),

  )

  actorName should {

    "receive a response to query1 with more time for the system to start up" in {
      eventually(Timeout(30.seconds), Interval(2.seconds)) {
        Await.result((actor ? GetMetadataAction(query1)).mapTo[SuccessfulMetadataJsonResponse], 1.seconds)
      }
    }

    testCases foreach { case (name, query, expectation) =>

      s"perform $name correctly" in {
        eventually(Timeout(10.seconds), Interval(2.seconds)) {
          val response = Await.result((actor ? GetMetadataAction(query)).mapTo[SuccessfulMetadataJsonResponse], 1.seconds)

          response.responseJson shouldBe expectation.parseJson
        }
      }
    }

    "be able to query by Unarchived metadataArchiveStatus" in {

      val summarizableStatusUpdate = PutMetadataAction(MetadataEvent(
        MetadataKey(workflowId, None, WorkflowMetadataKeys.Status),
        Option(MetadataValue("Running")),
        moment.plusSeconds(1)
      ))
      actor ! summarizableStatusUpdate

      eventually(Timeout(10.seconds), Interval(2.seconds)) {
        val queryEverythingResponse = Await.result((actor ? QueryForWorkflowsMatchingParameters(List.empty)).mapTo[WorkflowQuerySuccess], 1.seconds)
        queryEverythingResponse.response.results.length should be(1)
        println(queryEverythingResponse)

        val response1 = Await.result((actor ? QueryForWorkflowsMatchingParameters(List(("metadataArchiveStatus", "Unarchived")))).mapTo[WorkflowQuerySuccess], 1.seconds)
        // We submitted one workflow, so we should should see one value here:
        response1.response.results.length should be(1)
      }
    }

    "be able to query by Archived metadataArchiveStatus" in {

      val response2 = Await.result((actor ? QueryForWorkflowsMatchingParameters(List(("metadataArchiveStatus", "Archived")))).mapTo[WorkflowQuerySuccess], 1.seconds)
      // That workflow hasn't been marked as archived so this result set is empty:
      response2.response.results.length should be(0)
    }

    "be able to query by ArchiveFailed metadataArchiveStatus" in {
      val response3 = Await.result((actor ? QueryForWorkflowsMatchingParameters(List(("metadataArchiveStatus", "ArchiveFailed")))).mapTo[WorkflowQuerySuccess], 1.seconds)
      // That workflow hasn't been marked as archived so this result set is empty:
      response3.response.results.length should be(0)
    }

    "not be able to query by an invalid metadataArchiveStatus" in {
      val response4 = Await.result((actor ? QueryForWorkflowsMatchingParameters(List(("metadataArchiveStatus", "!!OOPS!!")))).mapTo[WorkflowQueryFailure], 1.seconds)
      // That workflow hasn't been marked as archived so this result set is empty:
      response4.reason.getMessage should be("Unrecognized 'metadata archive status' value(s): No such MetadataArchiveStatus: !!OOPS!!")
    }
  }
}

object MetadataServiceActorSpec {
  val Config: String =
    """
      |services.MetadataService.config.db-batch-size = 3
      |services.MetadataService.config.db-flush-rate = 100 millis
    """.stripMargin

  val ConfigWithoutSummarizer: String = Config + """
      |services.MetadataService.config.metadata-summary-refresh-interval = "Inf"
    """.stripMargin

  // Use this to convert the above "global" configs into metadata service specific "service config"s:
  def globalConfigToMetadataServiceConfig(config: Config): Config = if (config.hasPath("services.MetadataService.config")) {
    config.getConfig("services.MetadataService.config")
  } else {
    ConfigFactory.empty()
  }
}
