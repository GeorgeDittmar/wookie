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
package com.webtrends.harness.health

import ComponentState.ComponentState
import com.webtrends.harness.utils.{Json, JsonSerializable}
import org.joda.time.DateTime

case class ApplicationHealth(applicationName: String,
                             version: String,
                             time: DateTime,
                             state: ComponentState,
                             details: String,
                             components: Seq[HealthComponent]) extends JsonSerializable {
  override def toJson(): String = {
    val props = Map[String, Any](
      "applicationName" -> applicationName,
      "version" -> version,
      "state" -> state.toString,
      "details" -> details,
      "components" -> components
    )
    Json.build(props).toString
  }
}

case class ComponentHealth(state: ComponentState, details: String)
