/*
 * Copyright 2015 Webtrends (http://www.webtrends.com)
 *
 * See the LICENCE.txt file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webtrends.harness.component.zookeeper

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout
import com.webtrends.harness.component.zookeeper.ZookeeperEvent.Internal.{UnregisterZookeeperEvent, RegisterZookeeperEvent}
import com.webtrends.harness.component.zookeeper.ZookeeperEvent.ZookeeperEventRegistration
import scala.concurrent.Future
import java.util.concurrent.TimeUnit

private[harness] class ZookeeperService()(implicit system: ActorSystem) {

  import ZookeeperService._

  private[zookeeper] val defaultTimeout = Timeout(system.settings.config.getDuration("message-processor.default-send-timeout", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
  private val log = Logging(system, this.getClass)

  /**
   * Register for subscription events. This is not used for maintaining
   * subscriptions, but can be used more for testing subscription events.
   * @param registrar the actor that is to receive the events
   * @param to the class to register for
   */
  def register(registrar: ActorRef, to: ZookeeperEventRegistration): Unit =
    mediator.get ! RegisterZookeeperEvent(registrar, to)

  /**
   * Unregister for subscription events. This is not used for maintaining
   * subscriptions, but can be used more for testing subscription events.
   * @param registrar the actor that is to receive the events
   * @param to the class to register for
   */
  def unregister(registrar: ActorRef, to: ZookeeperEventRegistration): Unit =
    mediator.foreach( _ ! UnregisterZookeeperEvent(registrar, to))

  /**
   * Set data in Zookeeper for the given path
   * @param path the path to set data in
   * @param data the data to set
   * @param create should the node be created if it does not exist
   * @param ephemeral should the created node be ephemeral
   * @param namespace an optional name space
   * @return the length of data that was written
   */
  def setData(path: String, data: Array[Byte], create: Boolean = false, ephemeral: Boolean = false, namespace: Option[String] = None)
             (implicit timeout: akka.util.Timeout = defaultTimeout): Future[Int] =
    (mediator.get ? SetPathData(path, data, create, ephemeral, namespace)).mapTo[Int]

  /**
   * Get Zookeeper data for the given path
   * @param path the path to get data from
   * @param namespace an optional name space
   * @return An instance of Array[Byte] or an empty array
   */
  def getData(path: String, namespace: Option[String] = None)(implicit timeout: akka.util.Timeout = defaultTimeout): Future[Array[Byte]] = {
    (mediator.get ? GetPathData(path, namespace)).mapTo[Array[Byte]]
  }

  /**
   * Get the data in Zookeeper for the given path or set it if the path does not exist
   * @param path the path to set data in
   * @param data the data to set
   * @param ephemeral should the created node be ephemeral
   * @param namespace an optional name space
   * @return An instance of Array[Byte] or an empty array
   */
  def getOrSetData(path: String, data: Array[Byte], ephemeral: Boolean = false, namespace: Option[String] = None)
                  (implicit timeout: akka.util.Timeout = defaultTimeout): Future[Array[Byte]] =
    (mediator.get ? GetOrSetPathData(path, data, ephemeral, namespace)).mapTo[Array[Byte]]

  /**
   * Get the child nodes for the given path
   * @param path the path to get the children of
   * @param includeData should the children's data be returned. Defaults to false.
   * @param namespace an optional name space
   * @return A Seq[String] or Nil is an error occurs or if there no children
   */
  def getChildren(path: String, includeData: Boolean = false, namespace: Option[String] = None)
                 (implicit timeout: akka.util.Timeout = defaultTimeout): Future[Seq[(String, Option[Array[Byte]])]] = {
    (mediator.get ? GetPathChildren(path, includeData, namespace)).mapTo[Seq[(String, Option[Array[Byte]])]]
  }

  /**
   * Create a node at the given path
   * @param path the path to create the node at
   * @param ephemeral is the node ephemeral
   * @param data the data to set in the node
   * @param namespace an optional name space
   * @return the full path to the newly created node or an empty string if an error occurred
   */
  def createNode(path: String, ephemeral: Boolean, data: Option[Array[Byte]], namespace: Option[String] = None)
                (implicit timeout: akka.util.Timeout = defaultTimeout): Future[String] = {
    (mediator.get ? CreateNode(path, ephemeral, data, namespace)).mapTo[String]
  }

  /**
   * Delete a node at the given path
   * @param path the path to delete the node at
   * @param namespace an optional name space
   * @return the full path to the newly created node or an empty string if an error occurred
   */
  def deleteNode(path: String, namespace: Option[String] = None)
                (implicit timeout: akka.util.Timeout = defaultTimeout): Future[String] = {
    (mediator.get ? DeleteNode(path, namespace)).mapTo[String]
  }

  /**
   * Does the node exist
   * @param path the path to check
   * @param namespace an optional name space
   * @return true or false
   */
  def nodeExists(path: String, namespace: Option[String] = None)
                (implicit timeout: akka.util.Timeout = defaultTimeout): Future[Boolean] = {
    (mediator.get ? GetNodeExists(path, namespace)).mapTo[Boolean]
  }
}

object ZookeeperService {

  def apply()(implicit system: ActorSystem): ZookeeperService = new ZookeeperService

  private var mediator: Option[ActorRef] = None

  private[harness] def registerMediator(actor: ActorRef) = {
    mediator = Some(actor)
  }

  private[harness] def unregisterMediator(actor: ActorRef) = {
    mediator = None
  }

  @SerialVersionUID(1L) private[harness] case class SetPathData(path: String, data: Array[Byte],
                                                                  create: Boolean = false, ephemeral: Boolean = false, namespace: Option[String] = None)

  @SerialVersionUID(1L) private[harness] case class GetPathData(path: String, namespace: Option[String] = None)

  @SerialVersionUID(1L) private[harness] case class GetOrSetPathData(path: String, data: Array[Byte],
                                                                       ephemeral: Boolean = false, namespace: Option[String] = None)

  @SerialVersionUID(1L) private[harness] case class GetPathChildren(path: String, includeData: Boolean, namespace: Option[String] = None)

  @SerialVersionUID(1L) private[harness] case class CreateNode(path: String, ephemeral: Boolean, data: Option[Array[Byte]], namespace: Option[String] = None)

  @SerialVersionUID(1L) private[harness] case class GetNodeExists(path: String, namespace: Option[String] = None)

  @SerialVersionUID(1L) private[harness] case class DeleteNode(path: String, namespace: Option[String] = None)

}

trait ZookeeperAdapter {
  this: Actor =>

  import this.context.system

  private lazy val zkService = ZookeeperService()

  /**
   * Set data in Zookeeper for the given path
   * @param path the path to set data in
   * @param data the data to set
   * @param create should the node be created if it does not exist
   * @param ephemeral should the created node be ephemeral
   * @return the length of data that was written
   */
  def setData(path: String, data: Array[Byte], create: Boolean = false, ephemeral: Boolean = false)
             (implicit timeout: akka.util.Timeout): Future[Int] = zkService.setData(path, data, create, ephemeral)

  /**
   * Set data in Zookeeper for the given path and namespace
   * @param namespace the name space
   * @param path the path to set data in
   * @param data the data to set
   * @param create should the node be created if it does not exist
   * @param ephemeral should the created node be ephemeral
   * @return the length of data that was written
   */
  def setDataWithNamespace(namespace: String, path: String, data: Array[Byte], create: Boolean = false, ephemeral: Boolean = false)
                          (implicit timeout: akka.util.Timeout): Future[Int] = zkService.setData(path, data, create, ephemeral, Some(namespace))

  /**
   * Get Zookeeper data for the given path
   * @param path the path to get data from
   * @return An instance of Array[Byte] or an empty array
   */
  def getData(path: String)(implicit timeout: akka.util.Timeout): Future[Array[Byte]] = zkService.getData(path)

  /**
   * Get Zookeeper data for the given path
   * @param namespace the name space
   * @param path the path to get data from
   * @return An instance of Array[Byte] or an empty array
   */
  def getDataWithNamespace(namespace: String, path: String)(implicit timeout: akka.util.Timeout): Future[Array[Byte]] =
    zkService.getData(path, Some(namespace))

  /**
   * Get the data in Zookeeper for the given path or set it if the path does not exist
   * @param path the path to set data in
   * @param data the data to set
   * @param ephemeral should the created node be ephemeral
   * @return An instance of Array[Byte] or an empty array
   */
  def getOrSetData(path: String, data: Array[Byte], ephemeral: Boolean = false)
                  (implicit timeout: akka.util.Timeout): Future[Array[Byte]] = zkService.getOrSetData(path, data, ephemeral)

  /**
   * Get the data in Zookeeper for the given path or set it if the path does not exist
   * @param namespace the name space
   * @param path the path to set data in
   * @param data the data to set
   * @param ephemeral should the created node be ephemeral
   * @return An instance of Array[Byte] or an empty array
   */
  def getOrSetDataWithNamespace(namespace: String, path: String, data: Array[Byte], ephemeral: Boolean = false)
                               (implicit timeout: akka.util.Timeout): Future[Array[Byte]] = zkService.getOrSetData(path, data, ephemeral, Some(namespace))

  /**
   * Get the child nodes for the given path
   * @param path the path to get the children of
   * @param includeData should the children's data be returned. Defaults to false.
   * @return A Seq[String] or Nil is an error occurs or if there no children
   */
  def getChildren(path: String, includeData: Boolean = false)
                 (implicit timeout: akka.util.Timeout): Future[Seq[(String, Option[Array[Byte]])]] =
    zkService.getChildren(path, includeData)

  /**
   * Get the child nodes for the given path
   * @param namespace the name space
   * @param path the path to get the children of
   * @param includeData should the children's data be returned. Defaults to false.
   * @return A Seq[String] or Nil is an error occurs or if there no children
   */
  def getChildrenWithNamespace(namespace: String, path: String, includeData: Boolean = false)
                              (implicit timeout: akka.util.Timeout): Future[Seq[(String, Option[Array[Byte]])]] =
    zkService.getChildren(path, includeData, Some(namespace))

  /**
   * Does the node exist
   * @param path the path to check
   * @return true or false
   */
  def nodeExists(path: String)
                (implicit timeout: akka.util.Timeout): Future[Boolean] =
    zkService.nodeExists(path, None)

  /**
   * Does the node exist
   * @param namespace the name space
   * @param path the path to check
   * @return true or false
   */
  def nodeExistsWithNamespace(namespace: String, path: String)
                             (implicit timeout: akka.util.Timeout): Future[Boolean] =
    zkService.nodeExists(path, Some(namespace))

  /**
   * Create a node at the given path
   * @param path the path to create the node at
   * @param ephemeral is the node ephemeral
   * @param data the data to set in the node
   * @return the full path to the newly created node
   */
  def createNode(path: String, ephemeral: Boolean, data: Option[Array[Byte]])
                (implicit timeout: akka.util.Timeout): Future[String] = zkService.createNode(path, ephemeral, data)

  /**
   * Create a node at the given path
   * @param namespace the name space
   * @param path the path to create the node at
   * @param ephemeral is the node ephemeral
   * @param data the data to set in the node
   * @return the full path to the newly created node
   */
  def createNodeWithNamespace(namespace: String, path: String, ephemeral: Boolean, data: Option[Array[Byte]])
                             (implicit timeout: akka.util.Timeout): Future[String] = zkService.createNode(path, ephemeral, data, Some(namespace))

  /**
   * Delete a node at the given path
   * @param path the path to create the node at
   * @return the full path to the newly created node
   */
  def deleteNode(path: String)
                (implicit timeout: akka.util.Timeout): Future[String] = zkService.deleteNode(path)

  /**
   * Delete a node at the given path
   * @param namespace the name space
   * @param path the path to delete the node at
   * @return the full path to the newly created node
   */
  def deleteNodeWithNamespace(namespace: String, path: String)
                             (implicit timeout: akka.util.Timeout): Future[String] = zkService.deleteNode(path, Some(namespace))
}
