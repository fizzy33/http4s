/*
 * Copyright 2013 http4s.org
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

package org.http4s

import cats.effect.IO
import fs2.Stream
import fs2.text.utf8Encode
import org.http4s.headers._
import org.http4s.laws.discipline.ArbitraryInstances._
import org.scalacheck.effect._

import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class ServerSentEventSpec extends Http4sSuite {
  import ServerSentEvent._

  def toStream(s: String): Stream[IO, Byte] =
    Stream.emit(s).through(utf8Encode)

  test("decode should decode multi-line messages") {
    val stream = toStream("""
      |data: YHOO
      |data: +2
      |data: 10
      |""".stripMargin('|'))
    stream
      .through(ServerSentEvent.decoder)
      .compile
      .toVector
      .assertEquals(
        Vector(
          ServerSentEvent(data = "YHOO\n+2\n10")
        ))
  }

  test("decode should decode test stream") {
    val stream = toStream("""
      |: test stream
      |data: first event
      |id: 1
      |
      |data:second event
      |id
      |
      |data:  third event
      |""".stripMargin('|'))
    //test stream\n\ndata: first event\nid: 1\n\ndata:second event\nid\n\ndata:  third event\n")
    stream
      .through(ServerSentEvent.decoder)
      .compile
      .toVector
      .assertEquals(Vector(
        ServerSentEvent(data = "first event", id = Some(EventId("1"))),
        ServerSentEvent(data = "second event", id = Some(EventId.reset)),
        ServerSentEvent(data = " third event", id = None)
      ))
  }

  test("decode should fire empty events") {
    val stream = toStream("""
      |data
      |
      |data
      |data
      |
      |data:
      |""".stripMargin('|'))
    //test stream\n\ndata: first event\nid: 1\n\ndata:second event\nid\n\ndata:  third event\n")
    stream
      .through(ServerSentEvent.decoder)
      .compile
      .toVector
      .assertEquals(
        Vector(
          ServerSentEvent(data = ""),
          ServerSentEvent(data = "\n"),
          ServerSentEvent(data = "")
        ))
  }

  test("decode should ignore single space after colon") {
    val stream = toStream("""
      |data:test
      |
      |data: test
      |""".stripMargin('|'))
    //test stream\n\ndata: first event\nid: 1\n\ndata:second event\nid\n\ndata:  third event\n")
    stream
      .through(ServerSentEvent.decoder)
      .compile
      .toVector
      .assertEquals(
        Vector(
          ServerSentEvent(data = "test"),
          ServerSentEvent(data = "test")
        ))
  }

  test("encode should be consistent with decode") {
    PropF.forAllF { (sses: Vector[ServerSentEvent]) =>
      val roundTrip = Stream
        .emits(sses)
        .covary[IO]
        .through(ServerSentEvent.encoder)
        .through(ServerSentEvent.decoder)
        .compile
        .toVector
      val f = roundTrip.unsafeToFuture()
      val rt = Await.result(f, Duration(10, TimeUnit.SECONDS))
      if ( rt != sses ) {
        toString
      }
      roundTrip.assertEquals(sses)
    }
  }

  test("encode should handle leading spaces") {
    // This is a pathological case uncovered by scalacheck
    val sse = ServerSentEvent(" a", Some(" b"), Some(EventId(" c")), Some(1L))
    Stream
      .emit(sse)
      .covary[IO]
      .through(ServerSentEvent.encoder)
      .through(ServerSentEvent.decoder)
      .compile
      .last
      .assertEquals(Some(sse))
  }

  test("encode should handle multi-line strings") {
    // This is a pathological case uncovered by scalacheck
    val sse = ServerSentEvent("a\nb\nc a", Some(" b"), Some(EventId(" c")), Some(1L))
    Stream
      .emit(sse)
      .covary[IO]
      .through(ServerSentEvent.encoder)
      .through(ServerSentEvent.decoder)
      .compile
      .last
      .assertEquals(Some(sse))
  }

  val eventStream: Stream[IO, ServerSentEvent] =
    Stream.range(0, 5).map(i => ServerSentEvent(data = i.toString))
  test("EntityEncoder[ServerSentEvent] should set Content-Type to text/event-stream") {
    assertEquals(
      Response[IO]().withEntity(eventStream).contentType,
      Some(`Content-Type`(MediaType.`text/event-stream`)))
  }

  test("EntityEncoder[ServerSentEvent] should decode to original event stream") {
    for {
      r <- Response[IO]()
        .withEntity(eventStream)
        .body
        .through(ServerSentEvent.decoder)
        .compile
        .toVector
      e <- eventStream.compile.toVector
    } yield assertEquals(r, e)
  }

}
