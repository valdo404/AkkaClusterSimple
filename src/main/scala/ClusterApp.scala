
import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.routing._
import com.typesafe.config.ConfigFactory
import akka.pattern.ask
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.pattern.pipe

import akka.util.Timeout

case class Test(who: String)

case class Result(who: String)

case object Tell

class SimpleClusterListener extends Actor with ActorLogging {
  val cluster = Cluster(context.system)

  override def preStart(): Unit = {
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent], classOf[UnreachableMember])
  }

  override def postStop(): Unit = cluster.unsubscribe(self)

  def receive = {
    case MemberUp(member) =>
      log.info("Member is Up: {}", member.address)
    case UnreachableMember(member) =>
      log.info("Member detected as unreachable: {}", member)
    case MemberRemoved(member, previousStatus) =>
      log.info("Member is Removed: {} after {}",
        member.address, previousStatus)
    case _: MemberEvent => // ignore
  }
}


class Hotel extends Actor with ActorLogging {
  import context.dispatcher

  def receive = {
    case Test(who) =>
      Future successful Result(who) pipeTo sender()
  }
}


class HotelWorker extends Actor with ActorLogging {
  def receive = {
    case Test(who) =>
      (context child who).
        getOrElse(context actorOf(Props[Hotel], who)) tell(Test(who), self)
    case Result(who) =>
      log.info(who)
  }
}


object ClusterApp extends App {
  ClusterBackend.launch(Seq("2551").toArray)
  ClusterBackend.launch(Seq("2552").toArray)
  ClusterClient.launch(Seq("4000").toArray)
}

object ClusterBackend {
  def launch(args: Array[String]) = {
    val port = if (args.isEmpty) "0" else args(0)
    val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port").withFallback(ConfigFactory.load())

    val system = ActorSystem("test", config)

    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      def run() = {
        println(s"shutdown system $port")
        system.shutdown()
      }
    }))
  }
}

object ClusterClient {
  def launch(args: Array[String]) = {
    val port = if (args.isEmpty) "0" else args(0)
    val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port").withFallback(ConfigFactory.load())

    val system = ActorSystem("test", config)
    system.actorOf(Props[SimpleClusterListener], "listener")

    Thread.sleep(3000)

    val router:ActorRef = system.actorOf(
      ClusterRouterPool(RoundRobinPool(10),
        ClusterRouterPoolSettings(
          totalInstances = 10,
          maxInstancesPerNode = 1,
          allowLocalRoutees = true,
          useRole = None)).props(Props[HotelWorker]),
      name = "workers")


    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      def run() = {
        println(s"shutdown system $port")

        system.shutdown()
      }
    }))

    while(true) {
      Thread.sleep(3000)

      router ! Test("h1")
      router ! Test("h2")
      router ! Test("h3")

      //system.actorSelection("/user/workers/*/*") ! Tell
      //system.actorSelection("/user/workers/*/final") ! Tell
    }
  }
}
