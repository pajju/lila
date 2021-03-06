package controllers

import lila._
import views._
import setup._
import http.{ Context, BodyContext }

import play.api.mvc.Call
import play.api.data.Form

import scalaz.effects._

object Setup extends LilaController with TheftPrevention with RoundEventPerformer {

  private def forms = env.setup.formFactory
  private def processor = env.setup.processor
  private def friendConfigMemo = env.setup.friendConfigMemo
  private def joiner = env.setup.friendJoiner
  private def gameRepo = env.game.gameRepo
  private def bookmarkApi = env.bookmark.api

  val aiForm = Open { implicit ctx ⇒
    IOk(forms aiFilled get("fen") map { html.setup.ai(_) })
  }

  val ai = process(forms.ai) { config ⇒
    implicit ctx ⇒
      processor ai config map { pov ⇒
        routes.Round.player(pov.fullId)
      }
  }

  val friendForm = Open { implicit ctx ⇒
    IOk(forms friendFilled get("fen") map { html.setup.friend(_) })
  }

  val friend = process(forms.friend) { config ⇒
    implicit ctx ⇒
      processor friend config map { pov ⇒
        routes.Setup.await(pov.fullId)
      }
  }

  val hookForm = Open { implicit ctx ⇒
    IOk(forms.hookFilled map { html.setup.hook(_) })
  }

  val hook = process(forms.hook) { config ⇒
    implicit ctx ⇒
      processor hook config map { hook ⇒
        routes.Lobby.hook(hook.ownerId)
      }
  }

  val filterForm = Open { implicit ctx ⇒
    IOk(forms.filterFilled map { html.setup.filter(_) })
  }

  val filter = OpenBody { implicit ctx ⇒
    implicit val req = ctx.body
    IOResult {
      forms.filter(ctx).bindFromRequest.fold(
        f ⇒ putStrLn(f.errors.toString) inject BadRequest(),
        config ⇒ processor filter config inject JsonOk(config.render)
      )
    }
  }

  def join(id: String) = Open { implicit ctx ⇒
    IOptionIOResult(gameRepo game id) { game ⇒
      joiner(game, ctx.me).fold(
        err ⇒ putFailures(err) map { _ ⇒
          Redirect(routes.Round.watcher(id, game.creatorColor.name))
        },
        _ flatMap {
          case (p, events) ⇒ performEvents(p.gameId)(events) map { _ ⇒
            Redirect(routes.Round.player(p.fullId))
          }
        })
    }
  }

  def await(fullId: String) = Open { implicit ctx ⇒
    IOptionResult(gameRepo pov fullId) { pov ⇒
      pov.game.started.fold(
        Redirect(routes.Round.player(pov.fullId)),
        PreventTheft(pov) {
          Ok(html.setup.await(
            pov,
            version(pov.gameId),
            friendConfigMemo get pov.game.id))
        }
      )
    }
  }

  def cancel(fullId: String) = Open { implicit ctx ⇒
    IOptionIORedirect(gameRepo pov fullId) { pov ⇒
      pov.game.started.fold(
        io(routes.Round.player(pov.fullId)),
        for {
          _ ← gameRepo remove pov.game.id
          _ ← bookmarkApi removeByGame pov.game
        } yield routes.Lobby.home
      )
    }
  }

  val api = Open { implicit ctx ⇒
    JsonIOk(processor.api)
  }

  val validateFen = Open { implicit ctx ⇒
    {
      for {
        fen ← get("fen")
        parsed ← chess.format.Forsyth <<< fen
        strict = get("strict").isDefined
        if (parsed.situation playable strict)
        validated = chess.format.Forsyth >> parsed
      } yield html.game.miniBoard(validated, parsed.situation.color.name)
    } fold (Ok(_), BadRequest)
  }

  private def process[A](form: Context ⇒ Form[A])(op: A ⇒ BodyContext ⇒ IO[Call]) =
    OpenBody { ctx ⇒
      implicit val req = ctx.body
      IORedirect(form(ctx).bindFromRequest.fold(
        f ⇒ putStrLn(f.errors.toString) inject routes.Lobby.home,
        config ⇒ op(config)(ctx)
      ))
    }

  private def version(gameId: String): Int =
    env.round.socket blockingVersion gameId
}
