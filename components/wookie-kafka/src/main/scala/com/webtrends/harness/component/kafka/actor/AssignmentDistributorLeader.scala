package com.webtrends.harness.component.kafka.actor


import akka.actor._
import akka.util.Timeout
import com.webtrends.harness.component.kafka.KafkaConsumerCoordinator
import com.webtrends.harness.component.kafka.KafkaConsumerCoordinator.TopicPartitionResp
import com.webtrends.harness.component.kafka.util.KafkaSettings
import com.webtrends.harness.component.zookeeper.{ZookeeperAdapter, ZookeeperEventAdapter}
import com.webtrends.harness.logging.ActorLoggingAdapter
import net.liftweb.json.{FieldSerializer, NoTypeHints, Serialization}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object AssignmentDistributorLeader {
  case class PartitionAssignment(topic: String, partition: Int, cluster: String, host: String) {
    def assignmentName: String = {
      s"$host-$topic-$partition"
    }
  }

  implicit val formats = Serialization.formats(NoTypeHints) +
                                              FieldSerializer[PartitionAssignment]()
  implicit val partsManifest = manifest[List[PartitionAssignment]]

  // Node data refresh
  case class DistributeAssignments(nodes: List[String], topicResp:TopicPartitionResp)

  //Tell the leader to refresh assignments
  case object RefreshNodeAssignments

  def props(sourceProxy: ActorRef) =
    Props(new AssignmentDistributorLeader(sourceProxy))
}
/**
 *
 */
class AssignmentDistributorLeader(sourceProxy: ActorRef)
  extends Actor
  with KafkaSettings
  with ActorLoggingAdapter
  with ZookeeperAdapter
  with ZookeeperEventAdapter {

  import AssignmentDistributorLeader._
  import AssignmentFetcher._
  import context.dispatcher

  implicit val timeout = Timeout(10 seconds)

  val paths = distributorPaths
  var scheduler: Option[Cancellable] = None
  //log.debug(kafkaConfig.root().render())
  val refresh = Try { kafkaConfig.getInt("consumer.assignment-distributor.assignment-refresh-seconds")
                    } getOrElse(20)

  val nodeName = self.path.name

  val fetcherActorName = s"$nodeName-fetcher"

  log.info(s"$nodeName Leader has started and will refresh assignments at $refresh sec(s)")

  override def postStop(): Unit = {
    log.info(s"AssignmentDistributor $nodeName stopping")
    scheduler.foreach(_.cancel())
  }

  override def receive: Receive = {
    case RefreshNodeAssignments => refreshNodeAssignments()

    case da: DistributeAssignments => distributeAssignments(da)

    case FetchTimeout  => log.error(s"${self.path.name} Fetch timeout!")
  }


  def refreshNodeAssignments() = {
    if(scheduler.isEmpty) scheduler =
      Some(context.system.scheduler.schedule( refresh seconds,
                                              refresh seconds, self, RefreshNodeAssignments))

    context.child(fetcherActorName) match {
      case None => context.actorOf(AssignmentFetcher.props(self, sourceProxy), fetcherActorName)

      case _ => log.warn(s"Can't fetch data because fetcher is active!")
    }
  }

  def distributeAssignments(assignmentInfo: DistributeAssignments) = {
    val nodes = assignmentInfo.nodes
    val assignsWithIndex = assignmentInfo.topicResp.partitionsByTopic.zipWithIndex
    val assignsByNode = (for (
      node <- nodes.zipWithIndex
    ) yield {
      node._1 -> assignsWithIndex.filter { assign =>
        (assign._2 >= node._2) && (assign._2 - node._2) % nodes.length == 0 }.map { x => x._1 }
    }).toMap

    log.debug(s"Setting new node assignments: ${assignsByNode.toString()}")
    setAssignmentsForNodes(assignsByNode.map { case (nodeId, partitionsByTopic) =>
      nodeId -> Serialization.write(partitionsByTopic)(formats)
    })
  }

  def setAssignmentsForNodes(data: Map[String, String]) = {
    // Delete data for nodes which aren't specified in the updated data
    getChildren(s"${paths.assignmentPath}", includeData = true).onComplete {
      case Success(existingData) =>
        // Delete any entries that don't have new data
        val nodesToDelete = existingData.filter{ case (nodeId, existingData) => !data.contains(nodeId)}

        Future.traverse(nodesToDelete) { case (nodeId, _) =>
          deleteNode(s"${paths.assignmentPath}/$nodeId")
        }.onFailure {
          case ex => log.error(s"Unable to delete assignment nodes - ${nodesToDelete.map(_._1)}", ex)
        }
      case Failure(fail) =>
        log.error(s"Unable to get list of nodes at ${paths.assignmentPath} " +
                  s"while attempting to delete stale node data. ${fail.getMessage()}")
    }

    Future.traverse(data) { case (nodeId, nodeData) =>
      setData(s"${paths.assignmentPath}/$nodeId", nodeData.getBytes(utf8), create = true, ephemeral = false)
    }.onComplete {
      case Success(path) =>
        log.debug(s"Successfully set node data")
      case Failure(fail) =>
        log.error(s"Unable to set node data. ${fail.getMessage()}")
    }
  }
}

object AssignmentFetcher {
  def props(receiver: ActorRef, sourceProxy: ActorRef) =
    Props(new AssignmentFetcher(receiver, sourceProxy))

  case object Fetch

  case class ConsumerNodes(nodes: List[String])

  case object FetchTimeout
}

/**
 * Actor is responsible for making requests to dependent services,
 * required for assignment distribution
 * @param receiver
 * @param sourceProxy
 */
class AssignmentFetcher(receiver: ActorRef, sourceProxy: ActorRef) extends Actor
  with ActorLoggingAdapter
  with ZookeeperAdapter
  with KafkaSettings
{
  import AssignmentDistributorLeader._
  import AssignmentFetcher._
  import KafkaConsumerProxy._
  import context.dispatcher

  implicit val timeout = Timeout(5 seconds)

  val configRoot = "wookie-kafka.consumer.assignment-distributor"

  val c = context.system.settings.config

  //log.debug(s"Assignment ${c.root().render()}")

  val fetchTimeout = Try {c.getLong(s"${configRoot}.fetch-timeout-millis")
                         } getOrElse(500L)

  log.debug(s"Fetching with timeout $fetchTimeout")

  var nodes: Option[List[String]] = None
  var topicPartitions: Option[TopicPartitionResp] = None

  val paths = distributorPaths

  self ! Fetch

  context.system.scheduler.scheduleOnce(fetchTimeout milliseconds) {
    self ! FetchTimeout
  }

  def receive: Actor.Receive = {
    case Fetch => fetch()

    case cn: ConsumerNodes =>
      nodes = Some(cn.nodes)
      isDone

    case tr: TopicPartitionResp =>
      topicPartitions = Some(tr)
      isDone

    case FetchTimeout => sendAndShutdown(FetchTimeout)
  }

  def isDone = (nodes, topicPartitions) match {
    case (Some(n), Some(tp)) =>
      log.debug(s"Got $nodes and $tp")

      sendAndShutdown(DistributeAssignments(n,tp))
    case _ =>
  }


  def sendAndShutdown(resp: Any): Unit = {
    receiver ! resp
    log.debug("Stopping Fetcher")
    context.stop(self)
  }

  def fetch() = {
    getChildren(s"${paths.nodePath}", includeData = false).onComplete {
      case Success(nodes) =>
        self ! ConsumerNodes(nodes.map{node => node._1}.toList)
      case Failure(fail) =>
        log.error(s"Not requesting node data update because " +
          s"I am unable to get list of nodes at ${paths.nodePath}. ${fail.getMessage()}")
    }

    sourceProxy ! TopicPartitionReq
  }
}