/*
 * Copyright 2014 Heiko Seeberger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package name.heikoseeberger.sbtgroll

import SbtGroll.GrollKey
import sbt.{ Keys, State }

private object Groll {
  def apply(state: State, grollArg: GrollArg): State =
    new Groll(state, grollArg)()
}

private class Groll(state: State, grollArg: GrollArg) {

  val buildDefinition = """.+sbt|project/.+\.scala""".r

  val baseDirectory = setting(Keys.baseDirectory, state)

  val historyRef = setting(GrollKey.historyRef, state)

  val workingBranch = setting(GrollKey.workingBranch, state)

  val git = Git(baseDirectory)

  val (currentId, currentMessage) = git.current()

  val history = git.history(historyRef)

  def apply(): State = {
    import GrollArg._
    grollArg match {
      case Show =>
        ifCurrentInHistory {
          state.log.info(s"== $currentId $currentMessage")
          state
        }
      case Next =>
        ifCurrentInHistory {
          groll(
            (history takeWhile { case (id, _) => id != currentId }).lastOption,
            "Already at the head of the commit history!",
            (id, message) => s">> $id $message"
          )
        }
      case Prev =>
        ifCurrentInHistory {
          groll(
            (history dropWhile { case (id, _) => id != currentId }).tail.headOption,
            "Already at the first commit!",
            (id, message) => s"<< $id $message"
          )
        }
      case Head =>
        groll(
          if (currentId == history.head._1) None else Some(history.head),
          "Already at the head of the commit history!",
          (id, message) => s">> $id $message"
        )
      case Initial =>
        groll(
          history find { case (_, message) => message startsWith "Initial state" },
          """There's no commit with a message starting with "Initial state"!""",
          (id, message) => s"<< $id $message"
        )
      case (Move(id)) =>
        groll(
          if (currentId == id) None else Some(id -> history.toMap.getOrElse(id, "")),
          s"""Already at "$id"""",
          (id, message) => s"<> $id $message"
        )
    }
  }

  def ifCurrentInHistory(block: => State): State = {
    if (!(history map fst contains currentId)) {
      state.log.warn(s"""Current commit "$currentId" is not part of the history defined by "$historyRef": Use "head", "initial" or "move"!""")
      state
    } else
      block
  }

  def groll(idAndMessage: Option[(String, String)], warn: => String, info: (String, String) => String): State =
    idAndMessage match {
      case None =>
        state.log.warn(warn)
        state
      case Some((id, message)) =>
        git.resetHard()
        git.clean()
        git.checkout(id, workingBranch)
        state.log.info(info(id, message))
        if (git.diff(id, currentId) exists (s => buildDefinition.pattern.matcher(s).matches))
          state.reload
        else
          state
    }
}
