package lila
package game

import DbGame._

import chess.{ Color, Variant, Status }
import chess.format.Forsyth
import round.Progress
import user.User

import com.novus.salat._
import com.novus.salat.dao._
import com.mongodb.casbah.{ WriteConcern, MongoCollection }
import com.mongodb.casbah.query.Imports._
import com.mongodb.casbah.map_reduce.MapReduceInlineOutput
import org.joda.time.DateTime
import org.scala_tools.time.Imports._
import java.util.Date
import scala.util.Random
import scalaz.effects._

final class GameRepo(collection: MongoCollection)
    extends SalatDAO[RawDbGame, String](collection) {

  def game(gameId: String): IO[Option[DbGame]] = io {
    if (gameId.size != gameIdSize) None
    else findOneById(gameId) flatMap (_.decode)
  }

  def game(query: DBObject): IO[Option[DbGame]] = io {
    super.findOne(query) flatMap (_.decode)
  }

  def player(gameId: String, color: Color): IO[Option[DbPlayer]] =
    game(gameId) map { gameOption ⇒
      gameOption map { _ player color }
    }

  def pov(gameId: String, color: Color): IO[Option[Pov]] =
    game(gameId) map { gameOption ⇒
      gameOption map { g ⇒ Pov(g, g player color) }
    }

  def pov(gameId: String, color: String): IO[Option[Pov]] =
    Color(color).fold(pov(gameId, _), io(None))

  def pov(fullId: String): IO[Option[Pov]] =
    game(fullId take gameIdSize) map { gameOption ⇒
      gameOption flatMap { g ⇒
        g player (fullId drop gameIdSize) map { Pov(g, _) }
      }
    }

  def pov(ref: PovRef): IO[Option[Pov]] = pov(ref.gameId, ref.color)

  def token(id: String): IO[String] = io {
    primitiveProjection[String](idSelector(id), "tk") | DbGame.defaultToken
  }

  def save(game: DbGame): IO[Unit] = io {
    update(idSelector(game), _grater asDBObject game.encode)
  }

  def save(progress: Progress): IO[Unit] =
    GameDiff(progress.origin.encode, progress.game.encode) |> {
      case (Nil, Nil) ⇒ io()
      case (sets, unsets) ⇒ {
        val fullSets = ("ua" -> new Date) :: sets
        val ops = unsets.isEmpty.fold(
          $set(fullSets: _*),
          $set(fullSets: _*) ++ $unset(unsets: _*)
        )
        val wc = WriteConcern.None
        io { collection.update(idSelector(progress.origin), ops, concern = wc) }
      }
    }

  def insert(game: DbGame): IO[Option[String]] = io {
    insert(game.encode)
  }

  // makes the asumption that player 0 is white!
  // proved to be true on prod DB at March 31 2012
  def setEloDiffs(id: String, white: Int, black: Int) = io {
    update(idSelector(id), $set("p.0.ed" -> white, "p.1.ed" -> black))
  }

  def setUser(id: String, color: Color, user: User) = io {
    val pn = "p.%d".format(color.fold(0, 1))
    update(idSelector(id), $set(pn + ".uid" -> user.id, pn + ".elo" -> user.elo))
  }

  def incBookmarks(id: String, value: Int) = io {
    update(idSelector(id), $inc("bm" -> value))
  }

  def finish(id: String, winnerId: Option[String]) = io {
    update(
      idSelector(id),
      winnerId.fold(userId ⇒
        $set("wid" -> userId),
        $set())
        ++ $unset(
          "c.t",
          "ph",
          "lmt",
          "p.0.previousMoveTs",
          "p.1.previousMoveTs",
          "p.0.lastDrawOffer",
          "p.1.lastDrawOffer",
          "p.0.isOfferingDraw",
          "p.1.isOfferingDraw",
          "p.0.isProposingTakeback",
          "p.1.isProposingTakeback"
        )
    )
  }

  def findRandomStandardCheckmate(distribution: Int): IO[Option[DbGame]] = io {
    find(Query.mate ++ ("v" $exists false))
      .sort(Query.sortCreated)
      .limit(1)
      .skip(Random nextInt distribution)
      .toList.map(_.decode).flatten.headOption
  }

  def denormalize(game: DbGame): IO[Unit] = io {
    val userIds = game.players.map(_.userId).flatten
    if (userIds.nonEmpty) update(idSelector(game), $set("uids" -> userIds))
    if (game.mode.rated) update(idSelector(game), $set("ra" -> true))
    if (game.variant.exotic) update(idSelector(game), $set("if" -> (Forsyth >> game.toChess)))
  }

  def saveNext(game: DbGame, nextId: String): IO[Unit] = io {
    update(
      idSelector(game),
      $set("next" -> nextId) ++
        $unset("p.0.isOfferingRematch", "p.1.isOfferingRematch")
    )
  }

  def initialFen(gameId: String): IO[Option[String]] = io {
    primitiveProjection[String](idSelector(gameId), "if")
  }

  val unplayedIds: IO[List[String]] = io {
    primitiveProjections[String](
      ("t" $lt 2) ++ ("ca" $lt (DateTime.now - 3.day) $gt (DateTime.now - 1.week)),
      "_id"
    )
  }

  // bookmarks and pgns should also be removed
  def remove(id: String): IO[Unit] = io {
    remove(idSelector(id))
  }

  // bookmarks and pgns should also be removed
  def removeIds(ids: List[String]): IO[Unit] = io {
    remove("_id" $in ids)
  }

  val candidatesToAutofinish: IO[List[DbGame]] = io {
    find(Query.playable ++
      Query.clock(true) ++
      ("ca" $gt (DateTime.now - 1.day)) ++ // index
      ("ua" $lt (DateTime.now - 2.hour))
    ).toList.map(_.decode).flatten
  }

  def abandoned(max: Int): IO[List[DbGame]] = io {
    find(
      Query.notFinished ++ ("ua" $lt DbGame.abandonedDate)
    ).limit(max).toList.map(_.decode).flatten
  }

  val featuredCandidates: IO[List[DbGame]] = io {
    find(Query.playable ++
      Query.clock(true) ++
      ("t" $gt 1) ++
      ("ca" $gt (DateTime.now - 4.minutes)) ++
      ("ua" $gt (DateTime.now - 15.seconds))
    ).toList.map(_.decode).flatten
  }

  def count(query: DBObject): IO[Int] = io {
    super.count(query).toInt
  }

  def count(query: Query.type ⇒ DBObject): IO[Int] = count(query(Query))

  def exists(id: String) = count(idSelector(id)) map (_ > 0)

  def recentGames(limit: Int): IO[List[DbGame]] = io {
    find(Query.started ++ Query.turnsGt(1))
      .sort(Query.sortCreated)
      .limit(limit)
      .toList.map(_.decode).flatten
  }

  def games(ids: List[String]): IO[List[DbGame]] = io {
    find("_id" $in ids).toList.map(_.decode).flatten
  } map { gs ⇒
    val gsMap = gs.map(g ⇒ g.id -> g).toMap
    ids.map(gsMap.get).flatten
  }

  def nbPerDay(days: Int): IO[List[Int]] = ((days to 1 by -1).toList map { day ⇒
    val from = DateTime.now.withTimeAtStartOfDay - day.days
    val to = from + 1.day
    count(("ca" $gte from $lt to))
  }).sequence

  def recentAverageElo(minutes: Int): IO[(Int, Int)] = io {
    val result = collection.mapReduce(
      mapFunction = """function() { 
        emit(!!this.ra, this.p); 
      }""",
      reduceFunction = """function(rated, values) {
  var sum = 0, nb = 0;
  values.forEach(function(game) {
    if(typeof game[0] != "undefined") {
      game.forEach(function(player) {
        if(player.elo) {
          sum += player.elo; 
          ++nb;
        }
      });
    }
  });
  return nb == 0 ? nb : Math.round(sum / nb);
}""",
      output = MapReduceInlineOutput,
      query = Some {
        ("ca" $gte (DateTime.now - minutes.minutes)) ++ ("p.elo" $exists true)
      }
    )
    (for {
      ratedRow ← result.hasNext option result.next
      rated ← ratedRow.getAs[Double]("value")
      casualRow ← result.hasNext option result.next
      casual ← casualRow.getAs[Double]("value")
    } yield rated.toInt -> casual.toInt) | (0, 0)
  }

  private def idSelector(game: DbGame): DBObject = idSelector(game.id)
  private def idSelector(id: String): DBObject = DBObject("_id" -> id)
}
