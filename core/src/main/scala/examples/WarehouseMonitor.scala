package join_patterns.examples.warehouse_monitor

import actor.*
import actor.Result.*
import join_patterns.MatchingAlgorithm
import join_patterns.receive

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import java.net.{ServerSocket, Socket}
import java.net.{DatagramPacket, DatagramSocket, InetAddress}
import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import scala.concurrent.Future
import scala.annotation.tailrec
import join_patterns.examples.printResult
import scala.util.{Success, Failure}
import warehouse_messages.warehouse.{Msg, Meta, PartReq, PartOK, Pos, ClosingTime};
import java.io.{InputStream, ByteArrayInputStream}
import java.nio.charset.StandardCharsets


/*
// Not using Protocol Buffers
enum EventType:
  case PartReq(id: String, lbj: String)
  case PartOK(part: String, lbj: String)
  case Pos(position: String, part: String, lbj: String)
  case ClosingTime(timeOfDay: String, lbj: String)

sealed trait EventType
case class PartReq(id: String, lbj: String) extends EventType
case class PartOK(part: String, lbj: String) extends EventType
case class Pos(position: String, part: String, lbj: String) extends EventType
case class ClosingTime(timeOfDay: String, lbj: String) extends EventType

type Event = EventType


// Can not make this work. Get things like
// Received from /127.0.0.1:51722 → {"id":"windshield","lbj":"null","type":"PartReq"}
// Left(DecodingFailure at : type EventType has no class/object/case named 'PartReq'.)
// Works if e.g. value of type field is always lowercase and we match with contructor names turned lowercase
object EventType {
  given Configuration = Configuration.default
    .withDiscriminator("type")
    .withTransformConstructorNames(_.toLowerCase)

  given Codec[EventType] = Codec.AsObject.derivedConfigured
} */

type Event = PartReq | PartOK | Pos | ClosingTime


def monitor(algorithm: MatchingAlgorithm) =
  Actor[Event, Unit] {
    receive { (self: ActorRef[Event]) =>
      {
       case (PartReq(part1, lbj1, meta1, _), Pos(position, part2, lbj2, meta2, _), PartOK(part3, lbj3, meta3, _)) if part1 == part2 && part2 == part3  =>
          println(
            s"========================= ${Console.BLUE}${Console.UNDERLINED}Join Pattern 01${Console.RESET} =========================\n"
          )
          println(
            s"${Console.BLUE}${Console.UNDERLINED}Matched messages: PartReq(id = $part1, ...), Pos(position = $position, id = $part2, ...), PartOK(id = $part3, ...)${Console.RESET}\n"
          )
          println(
            s"\n========================= ${Console.BLUE}${Console.UNDERLINED}Join Pattern 01${Console.RESET} ========================="
          )
          Continue
        case (PartReq(part1, lbj1, meta1, _),Pos(position, part2, lbj2, meta2, _), PartOK(part3, lbj3, meta3, _)) if part2 == "broken part" && part2 == part3 && lbj2 == lbj3 =>
          println(
            s"========================= ${Console.YELLOW}${Console.UNDERLINED}Join Pattern 02${Console.RESET} =========================\n"
          )
          println(
            s"${Console.YELLOW}${Console.UNDERLINED}Matched messages: PartReq(id = $part1, ...), PartReq(position = $position, id = $part2, ...), PartOK(id = $part3, ...)${Console.RESET}\n"
          )
          println(
            s"\n========================= ${Console.YELLOW}${Console.UNDERLINED}Join Pattern 02${Console.RESET} ========================="
          )
          Continue
        case ClosingTime(time, lbj, meta, _) =>
          println(
            s"${Console.RED}${Console.UNDERLINED}Matched messages: ClosingTime(timeOfDay = $time, ...)${Console.RESET}\n"
          )
          println(
            s"${Console.RED}${Console.UNDERLINED}Shutting down monitor actor...${Console.RESET}"
          )
          Stop(())
      }
    }(algorithm)
}

def extractMsg(msg: Msg): Option[Event] = msg.kind match {
    case Msg.Kind.PartReq(partReq) => Some(partReq)
    case Msg.Kind.PartOK(partOK) => Some(partOK)
    case Msg.Kind.Pos(pos) => Some(pos)
    case Msg.Kind.ClosingTime(closingTime) => Some(closingTime)
    case _ => None
  }

def runWarehouseMonitor(algorithm: MatchingAlgorithm) =
  val (monitorFut, monitorRef) = monitor(algorithm).start()
  val port = 9999
  val socket = new DatagramSocket(port)

  println("monitor ready")
  //Await.ready(receiveLoop(socket, monitorFut, monitorRef), Duration(15, "s"))
  receiveLoop(socket, monitorFut, monitorRef)

@tailrec
def receiveLoop(socket: DatagramSocket, monitorFut: Future[Unit], monitorRef: ActorRef[Event]): Unit = {
    val bufferSize = 4096
    val packet = new DatagramPacket(new Array[Byte](bufferSize), bufferSize)
    // Receive a packet (blocking)
    socket.receive(packet)
    // extract payload from packet, remove any trailing 0s
    val data = java.util.Arrays.copyOfRange(packet.getData, packet.getOffset, packet.getOffset + packet.getLength)

    extractMsg(Msg.parseFrom(data)) match
      case Some(msg) => monitorRef ! msg
      case None => println(s"Error parsing message: ${Msg.parseFrom(data)}")

    /*
    // not using protocol buffers
    decode[EventType](message) match
        case Right(msg) => monitorRef ! msg
        case Left(error) => println(error) */

    // Tail-recursive call to continue receiving
    receiveLoop(socket, monitorFut, monitorRef)
}

