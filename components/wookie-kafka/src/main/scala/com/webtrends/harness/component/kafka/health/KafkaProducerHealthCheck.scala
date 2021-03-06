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

package com.webtrends.harness.component.kafka.health

import com.webtrends.harness.component.kafka.actor.KafkaProducer
import com.webtrends.harness.health.ComponentState.ComponentState
import com.webtrends.harness.health.{ComponentState, HealthComponent}
import com.webtrends.harness.service.messages.CheckHealth

case object KafkaHealthRequest
case class SetHealth(health: ComponentState, details: String)

trait KafkaProducerHealthCheck { this: KafkaProducer =>
  var currentHealth: Option[HealthComponent] = None

  def healthReceive: Receive = {
    case CheckHealth => sender ! getCurrentHealth
    case s: SetHealth => setHealth(s.health, s.details)
  }

  def getCurrentHealth: HealthComponent = {
    if (!currentHealth.isDefined) {
      currentHealth = Some(HealthComponent("Kafka Writer", ComponentState.NORMAL, "No data has been written yet"))
    }
    currentHealth.get
  }

  def setHealth(componentState: ComponentState, details: String) {
    currentHealth = Some(HealthComponent("Kafka Writer", componentState, details))
  }
}
