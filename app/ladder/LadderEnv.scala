package lila
package ladder

import user.UserRepo
import core.Settings

import com.mongodb.casbah.MongoCollection
import scalaz.effects._
import akka.actor.Props
import play.api.libs.concurrent._
import play.api.Application

final class LadderEnv(
    app: Application,
    userRepo: UserRepo,
    settings: Settings,
    mongodb: String ⇒ MongoCollection) {

  import settings._

  private implicit val ctx = app

  lazy val api = new LadderApi(
    ladderRepo = ladderRepo,
    ladRepo = ladRepo,
    paginator = paginator,
    organizer = organizer)

  private lazy val ladderRepo = new LadderRepo(mongodb(LadderCollectionLadder))
  private lazy val ladRepo = new LadRepo(mongodb(LadderCollectionLad))

  private lazy val paginator = new PaginatorBuilder(
    ladRepo = ladRepo,
    userRepo = userRepo,
    maxPerPage = LadderPaginatorMaxPerPage)

  private lazy val organizer = Akka.system.actorOf(Props(new Organizer(
    ladRepo = ladRepo
  )), name = ActorLadderOrganizer)
}