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
package com.webtrends.harness.component

import java.io.File
import java.nio.file.FileSystems

import akka.actor._
import akka.pattern._
import com.typesafe.config.{ConfigValueType, ConfigException, Config, ConfigFactory}
import com.webtrends.harness.HarnessConstants
import com.webtrends.harness.logging.Logger
import scala.util.control.Exception._
import com.webtrends.harness.utils.{ConfigUtil, FileUtil}
import com.webtrends.harness.app.HarnessActor.{ConfigChange, SystemReady, ComponentInitializationComplete}
import com.webtrends.harness.app.{PrepareForShutdown, HActor, Harness, HarnessClassLoader}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Promise, Future}
import scala.util.{Success, Failure}
import scala.collection.JavaConverters._

case class Request[T](name:String, msg:ComponentRequest[T])
case class Message[T](name:String, msg:ComponentMessage[T])
case class InitializeComponents()
case class LoadComponent(name:String, classPath:String)
case class ComponentStarted(name:String)
case class GetComponent(name:String)

private[component] object ComponentState extends Enumeration {
  type ComponentState = Value
  val Initializing, Started, Failed = Value
}
import ComponentState._

object ComponentManager {
  private val externalLogger = Logger.getLogger(this.getClass)

  val components = mutable.Map[String, ComponentState]()

  val ComponentRef = "self"

  /**
   * Checks to see if all the components have started up
   *
   * @return
   */
  def isAllComponentsStarted : Boolean = {
    val groupedMap = components.groupBy(_._2).toList
    if (groupedMap.size == 1) {
      groupedMap(0)._1 == Started
    } else {
      false
    }
  }

  /**
   * Checks to see if there are any components that have failed
   *
   * @return Option, if failed will contain name of first component that failed
   */
  def failedComponents : Option[String] = {
    components foreach {
      case x if x._2 == Failed => return Some(x._1)
      case _ => //ignore the other matches
    }
    None
  }

  def props = Props[ComponentManager]

  val KeyManagerClass = "manager"
  val KeyEnabled = "enabled"

  def loadComponentJars(sysConfig:Config, loader:HarnessClassLoader) = {
    val loadedList = mutable.MutableList[String]()
    getComponentPath(sysConfig) match {
      case Some(dir) =>
        val files = dir.listFiles.filter(x => x.isDirectory | FileUtil.getExtension(x).equalsIgnoreCase("jar")) flatMap {
          f =>
            if (f.isDirectory) {
              val componentName = f.getName
              try {
                val co = validateComponentDir(componentName, f)
                // get list of all JARS and load each one
                Some(co._2.listFiles.filter(f => FileUtil.getExtension(f).equalsIgnoreCase("jar")) flatMap {
                  f =>
                    val jarName = FileUtil.getFilename(f)
                    if (loadedList.contains(jarName)) {
                      None
                    } else {
                      loadedList += jarName
                      Some(f.getCanonicalFile.toURL)
                    }
                })
              } catch {
                case e: IllegalArgumentException =>
                  externalLogger.warn(e.getMessage)
                  None
              }
            } else {
              if (loadedList.contains(FileUtil.getFilename(f))) None else Some(Array(f.getCanonicalFile.toURL))
            }
        } flatMap { x => x }
        // probably should do some de-duplication
        loader.addURLs(files)
      case None => // ignore
    }
  }

  /**
   * This function will load all the component Jars from the component location
   * It will check to make sure the structure of the component is valid and return a list
   * of valid enabled configs
   */
  def loadComponentInfo(sysConfig: Config): Seq[Config] = {
    getComponentPath(sysConfig) match {
      case Some(dir) =>
        val configs = dir.listFiles.filter(_.isDirectory) flatMap {
          f =>
            val componentName = f.getName
            try {
              val co = validateComponentDir(componentName, f)
              // reload config at this point so that it gets the defaults from the JARs
              val conf = allCatch either ConfigFactory.parseFile(co._1) match {
                case Left(fail) => externalLogger.warn(s"Failed to parse config file ${co._1.getAbsoluteFile}", fail); None
                case Right(value) => Some(value)
              }
              conf
            } catch {
              case e: IllegalArgumentException =>
                externalLogger.warn(e.getMessage)
                None
            }
        }
        configs
      case None => Seq[Config]()
    }

  }

  def getComponentPath(config:Config) : Option[File] = {
    val compDir = FileSystems.getDefault.getPath(ConfigUtil.getDefaultValue(HarnessConstants.KeyPathComponents, config.getString, "")).toFile
    compDir.exists() match {
      case true => Some(compDir)
      case false => None
    }
  }

  /**
   * Will validate a component directory and return the location of the component library dir
   *
   * @param componentName name of component .conf file
   * @param folder folder in which configs can be found
   * @return
   * @throws IllegalArgumentException if configuration file is not found, or the lib directory is not there
   */
  def validateComponentDir(componentName:String, folder:File) : (File, File) = {
    val confFile = folder.listFiles.filter(_.getName.equalsIgnoreCase("reference.conf"))
    require(confFile.size == 1, "Conf file not found.")
    // check the config file and if disabled then fail
    val config = ConfigFactory.parseFile(confFile(0))
    if (config.hasPath(s"$componentName.enabled")) {
      require(config.getBoolean(s"$componentName.enabled"), s"$componentName not enabled")
    }
    val libDir = folder.listFiles.filter(f => f.isDirectory && f.getName.equalsIgnoreCase("lib"))
    require(libDir.size == 1, "Lib directory not found.")
    (confFile(0), libDir(0))
  }
}

class ComponentManager extends PrepareForShutdown {
  import context.dispatcher
  val sysConfig = context.system.settings.config
  val componentTimeout = ConfigUtil.getDefaultTimeout(sysConfig, HarnessConstants.KeyComponentStartTimeout, 20 seconds)

  var componentsInitialized = false

  override def receive: Receive = initializing

  override def postStop() = {
    context.children.foreach(ref => ref ! StopComponent)
    super.postStop()
  }

  def initializing : Receive = super.receive orElse {
    case InitializeComponents => initializeComponents
    case ComponentStarted(name) => componentStarted(name)
    case LoadComponent(name, classPath) => sender ! loadComponentClass(name, classPath)
  }

  def started : Receive = super.receive orElse {
    case Message(name, msg) => message(name, msg)
    case Request(name, msg) => pipe(request(name, msg)) to sender
    case GetComponent(name) => sender ! context.child(name)
    case LoadComponent(name, classPath) => sender ! loadComponentClass(name, classPath)
    case SystemReady =>  context.children.foreach(ref => ref ! SystemReady)
    case c: ConfigChange =>
      log.info("Sending config change message to all components...")
      context.children.foreach(ref => ref ! c)
    case _ =>
      //throw all other messages to debug
      //log.debug("Message not handled by ComponentManager")
  }

  private def componentStarted(name:String) = {
    log.debug(s"Received start message from component $name")
    ComponentManager.components(name) = ComponentState.Started
    if (ComponentManager.isAllComponentsStarted) {
      validateComponentStartup()
    } else {
      ComponentManager.failedComponents match {
        case Some(name) =>
          log.error(s"Failed to load component [$name]")
          Harness.shutdown
        case None => //ignore
      }
    }
  }

  def message[T](name:String, msg:ComponentMessage[T]) = {
    // first check to see if
    context.child(name) match {
      case Some(ref) => ref ! msg
      case None => log.error(s"$name component not found")
    }
  }

  /**
   * A message can be sent from any where in the system to any component and get a response
   *
   * @param name name of actor component
   * @param msg message to send to component
   * @param wrapResponse default is true, but if you don't want to have the response wrapped
   *                     in a ComponentResponse, then set this to false
   * @return
   */
  def request[T, U](name:String, msg:ComponentRequest[T], wrapResponse:Boolean=true) : Future[ComponentResponse[U]] = {
    val p = Promise[ComponentResponse[U]]
    context.child(name) match {
      case Some(ref) =>
        val actorResp = (ref ? msg)(msg.timeout).mapTo[ComponentResponse[U]]
        actorResp onComplete {
          case Success(resp) => p success resp
          case Failure(f) => p failure f
        }
      case None => p failure ComponentNotFoundException("ComponentManager", s"$name component not found")
    }
    p.future
  }

  private def validateComponentStartup() = {
    // Wait for the child actors above to be loaded before calling on the services
    Future.traverse(context.children)(child => (child ? Identify("xyz123"))(componentTimeout)) onComplete {
      case Success(_) =>
        sendComponentInitMessage()
      case Failure(t) =>
        log.error("Error loading the component actors", t)
        // if the components failed to load then we will need to shutdown the system
        Harness.shutdown
    }
  }

  private def sendComponentInitMessage() = {
    if (!componentsInitialized) {
      // notify the harness actor that we are done
      context.parent ! ComponentInitializationComplete
      componentsInitialized = true
      context.become(started)
      log.debug("Component Manager Started")
    }
  }

  /**
   * Load up all the system components from the config
   * This function needs to block quite a bit because the system requires to load components
   * prior to anything else happening. This function will only be executed once.
   */
  private def initializeComponents = {
    val cList = ComponentManager.getComponentPath(sysConfig) match {
      case Some(dir) => dir.listFiles.filter(x => x.isDirectory || FileUtil.getExtension(x).equalsIgnoreCase("jar"))
      case None => Array[File]()
    }

    val libList = if (sysConfig.hasPath(HarnessConstants.KeyComponents)) {
      sysConfig.getStringList(HarnessConstants.KeyComponents).asScala
    } else {
      val tempList = mutable.MutableList[String]()
      // try find any dynamically, we may get duplicate entries, but that will be handled during the loading process
      sysConfig.root().asScala foreach {
        entry =>
          try {
            entry._2.valueType() match {
              case ConfigValueType.OBJECT =>
                val c = sysConfig.getConfig(entry._1)
                if (c.hasPath(HarnessConstants.KeyDynamicComponent)) {
                  if (c.getBoolean(HarnessConstants.KeyDynamicComponent)) {
                    log.info(s"Dynamic Component detected [${entry._1}]")
                    tempList += entry._1
                  }
                }
              case _ => //ignore
            }
          } catch {
            case e:ConfigException => // if this exception occurs we know for sure that it is not a dynamic component
          }
      }
      tempList.toList
    }

    val componentsLoaded = mutable.MutableList[String]()
    val compList = cList ++ libList
    if (compList.length > 0) {
      // load up configured components
      log.info("Loading components...")
      compList foreach {
        compFolder =>
          val cfName = compFolder.toString
          try {
            val componentName = compFolder match {
              case f:File => getComponentName(f)
              case s:String => s
              case x => x.toString
            }
            if (!componentsLoaded.contains(componentName)) {
              val config = ConfigUtil.prepareSubConfig(sysConfig, componentName)
              if (config.hasPath(ComponentManager.KeyEnabled) && !config.getBoolean(ComponentManager.KeyEnabled)) {
                log.info(s"Component $componentName not enabled, skipping.")
              } else {
                require(config.hasPath(ComponentManager.KeyManagerClass), "Manager for component not found.")
                val className = config.getString(ComponentManager.KeyManagerClass)
                loadComponentClass(componentName, className)
              }
              componentsLoaded += componentName
            }
          } catch {
            case d:NoClassDefFoundError =>
              componentLoadFailed(cfName, s"Could not load component [$compFolder]. No Class Def. This could be because the JAR for the component was not found in the component-path")
            case e:ClassNotFoundException =>
              componentLoadFailed(cfName, s"Could not load component [$cfName]. Class not found. This could be because the JAR for the component was not found in the component-path", Some(e))
            case nf:ComponentNotFoundException =>
              // this is the one case were we don't set the component as failed
              log.warning(s"Could not load component [$cfName]. Component invalid. Error: ${nf.getMessage}")
            case i:IllegalArgumentException =>
              // this is the one case were we don't set the component as failed
              log.warning(s"Could not load component [$cfName]. Component invalid. Error: ${i.getMessage}")
            case c:ConfigException =>
              componentLoadFailed(cfName, s"Could not load component [$cfName]. Configuration failure", Some(c))
          }
      }
      // schedule a timeout for all components to send back success start status, and after timeout check the status
      // and if not all started then shutdown the server
      val startTimeout = componentTimeout.duration
      context.system.scheduler.scheduleOnce(startTimeout,
        new Runnable() {
          def run = {
            checkStartupStatus()
          }
        })
    } else {
      log.info("No components registered.")
      sendComponentInitMessage()
    }
  }

  /**
   * This function will attempt various ways to find the component name of the jar.
   * 1. Will check to see if the jar filename is the component name
   * 2. Will check the mapping list from the config to see if the jar filename maps to a component name
   * 3. Will grab the first two segments of the filename separated by "-" and use that as the component name
   *    eg. "wookie-socko-1.0-SNAPSHOT.jar" will evaluate the component name to "wookie-socko"
   * It will perform the checks in the order above
   *
   * @param file The file or directory that is the jar or component dir
   * @return String option if found the name, None otherwise
   */
  def getComponentName(file:File) : String = {
    val config = context.system.settings.config
    val name = file.getName
    if (file.isDirectory) {
      ComponentManager.validateComponentDir(name, file)
      name
    } else {
      if (config.hasPath(name)) {
        name
      } else if (config.hasPath(s"${HarnessConstants.KeyComponentMapping}.$name")) {
        config.getString(s"${HarnessConstants.KeyComponentMapping}.$name")
      } else {
        val segments = name.split("-")
        // example wookie-socko-1.0-SNAPSHOT.jar
        val fn = segments(0) + "-" + segments(1)
        if (config.hasPath(s"$fn")) {
          fn
        } else {
          throw new ComponentNotFoundException("ComponentManager", s"$name component not found")
        }
      }
    }
  }

  /**
   * Will load a component class and initialize it
   * @param componentName Name of component
   * @param className Full class name
   * @param classLoader Class loader for harness
   */
  def loadComponentClass(componentName: String, className: String, classLoader: Option[HarnessClassLoader] = None): Option[ActorRef] = {
    val hClassLoader = classLoader match {
      case Some(loader) => loader
      case None => Thread.currentThread.getContextClassLoader.asInstanceOf[HarnessClassLoader]
    }
    require(className.nonEmpty, "Manager for component not set.")

    val clazz = hClassLoader.loadClass(className)
    var component = None: Option[ActorRef]
    clazz match {
      case c if classOf[Component].isAssignableFrom(c) =>
        component = initComponentActor(componentName, clazz)
      case _ =>
        log.warning(s"Could not load component [$componentName]. Not an instance of Component")
    }
    component
  }

  /**
   * Initializes the component actor
   *
   * @param componentName
   * @param clazz
   * @tparam T
   * @return
   */
  def initComponentActor[T](componentName:String, clazz:Class[T]) : Option[ActorRef] = {
    // check to see if the actor exists
    // need to block in this instance we don't want the system to start prior to
    // the system components to being fully loaded.
    context.child(componentName) match {
      case Some(ref) =>
        log.info(s"Component [$componentName] already loaded.")
        Some(ref)
      case None =>
        val ref = context.actorOf(Props(clazz, componentName), componentName)
        log.info(s"Loading component [$componentName] at path [${ref.path.toString}}]")
        // get the start timeout but default to the component timeout
        ComponentManager.components(componentName) = ComponentState.Initializing
        ref ! StartComponent
        Some(ref)
    }
  }

  /**
   * Will be executed after configured timeout period to make sure that our component manager doesn't just sit around
   * waiting for nothing. If not all components have started then we shut down the server, if it has then we send the
   * all clear message
   */
  private def checkStartupStatus() = {
    if (ComponentManager.isAllComponentsStarted) {
      validateComponentStartup()
    } else {
      log.info("Failed to startup components: " + ComponentManager.components.toString())
      Harness.shutdown
    }
  }

  private def componentLoadFailed(componentName:String, msg:String, ex:Option[Exception]=None) = {
    ex match {
      case Some(e) => log.error(msg, e)
      case None => log.error(msg)
    }
    ComponentManager.components(componentName) = ComponentState.Failed
  }
}