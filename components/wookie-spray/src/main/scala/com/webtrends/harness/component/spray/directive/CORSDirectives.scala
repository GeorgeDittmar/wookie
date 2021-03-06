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
package com.webtrends.harness.component.spray.directive

import spray.http.HttpHeaders._
import spray.http.StatusCodes.Forbidden
import spray.http.{AllOrigins, AllowedOrigins, HttpOrigin, SomeOrigins}
import spray.routing._

trait CORSDirectives extends BaseDirectives {
  def respondWithCORSHeaders(origin: AllowedOrigins) =
    respondWithHeaders(
      `Access-Control-Allow-Origin`(origin),
      `Access-Control-Allow-Credentials`(false))

  def corsFilter(allowedOrigins: AllowedOrigins)(route: Route) =
    allowedOrigins match {
      case AllOrigins =>
        respondWithCORSHeaders(AllOrigins)(route)
      case _ =>
        optionalHeaderValueByName("Origin") {
          case None => route
          case Some(clientOrigin) =>
            if (allowedOrigins == SomeOrigins(Seq(HttpOrigin(clientOrigin)))) {
              respondWithCORSHeaders(allowedOrigins)(route)
            } else {
              complete(Forbidden, Nil, "Invalid origin") // Maybe, a Rejection will fit better
            }
        }
    }
}