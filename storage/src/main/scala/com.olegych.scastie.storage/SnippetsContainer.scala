package com.olegych.scastie.storage

import com.olegych.scastie.api._
import com.olegych.scastie.instrumentation.Instrument
import com.olegych.scastie.util.Base64UUID

import net.lingala.zip4j.core.ZipFile
import net.lingala.zip4j.model.ZipParameters

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.{ExecutionContext, Future}

case class UserLogin(login: String)

trait SnippetsContainer {
  protected implicit val ec: ExecutionContext

  def appendOutput(progress: SnippetProgress): Future[Unit]
  def delete(snippetId: SnippetId): Future[Boolean]
  def listSnippets(user: UserLogin): Future[List[SnippetSummary]]
  def readOldSnippet(id: Int): Future[Option[FetchResult]]
  def readScalaJs(snippetId: SnippetId): Future[Option[FetchResultScalaJs]]
  def readScalaJsSourceMap(
      snippetId: SnippetId
  ): Future[Option[FetchResultScalaJsSourceMap]]
  def readSnippet(snippetId: SnippetId): Future[Option[FetchResult]]
  protected def insert(snippetId: SnippetId, inputs: Inputs): Future[Unit]

  final def save(inputs: Inputs, user: Option[UserLogin]): Future[SnippetId] =
    create(inputs.copy(isShowingInUserProfile = true), user)

  final def amend(snippetId: SnippetId, inputs: Inputs): Future[Boolean] =
    for {
      deleted <- delete(snippetId)
      _ <- insert0(snippetId, inputs) if (deleted)
    } yield deleted

  final def create(inputs: Inputs, user: Option[UserLogin]): Future[SnippetId] = {
    insert0(newSnippetId(user), inputs)
  }

  final def update(snippetId: SnippetId, inputs: Inputs): Future[Option[SnippetId]] = {
    updateSnippetId(snippetId).flatMap {
      case Some(nextSnippetId) => insert0(nextSnippetId, inputs).map(Some(_))
      case _                   => Future.successful(None)
    }
  }

  final def fork(snippetId: SnippetId, inputs: Inputs, user: Option[UserLogin]): Future[SnippetId] =
    create(inputs.copy(forked = Some(snippetId), isShowingInUserProfile = true), user)

  final def readScalaSource(
      snippetId: SnippetId
  ): Future[Option[FetchResultScalaSource]] =
    readSnippet(snippetId).map(
      _.flatMap(
        snippet =>
          Instrument(snippet.inputs.code, snippet.inputs.target) match {
            case Right(instrumented) =>
              Some(FetchResultScalaSource(instrumented))
            case _ => None
        }
      )
    )

  final def downloadSnippet(snippetId: SnippetId): Future[Option[Path]] =
    readSnippet(snippetId).map(_.map(asZip(snippetId)))

  protected final def newSnippetId(user: Option[UserLogin]): SnippetId = {
    val uuid = Base64UUID.create
    SnippetId(uuid, user.map(u => SnippetUserPart(u.login)))
  }

  protected final def updateSnippetId(snippetId: SnippetId): Future[Option[SnippetId]] = {
    snippetId.user match {
      case Some(SnippetUserPart(login, lastUpdateId)) =>
        val nextSnippetId = SnippetId(
          snippetId.base64UUID,
          Some(
            SnippetUserPart(
              login,
              lastUpdateId + 1
            )
          )
        )
        readSnippet(nextSnippetId).flatMap {
          case Some(_) => updateSnippetId(nextSnippetId)
          case None    => Future.successful(Some(nextSnippetId))
        }
      case None => Future.successful(None)
    }
  }

  private val snippetZip = Files.createTempDirectory(null)

  private def asZip(snippetId: SnippetId)(snippet: FetchResult): Path = {
    import snippet.inputs

    val projectDir = snippetZip.resolve(snippetId.url)

    if (!Files.exists(projectDir)) {
      Files.createDirectories(projectDir)

      val buildFile = projectDir.resolve("build.sbt")
      Files.write(buildFile, inputs.sbtConfig.getBytes)

      val projectFile = projectDir.resolve("project/plugins.sbt")
      Files.createDirectories(projectFile.getParent)
      Files.write(projectFile, inputs.sbtPluginsConfig.getBytes)

      val codeFile = projectDir.resolve("src/main/scala/main.scala")
      Files.createDirectories(codeFile.getParent)
      Files.write(codeFile, inputs.code.getBytes)
    }

    val zippedProjectDir = Paths.get(s"$projectDir.zip")
    if (!Files.exists(zippedProjectDir)) {
      new ZipFile(zippedProjectDir.toFile)
        .addFolder(projectDir.toFile, new ZipParameters())
    }

    zippedProjectDir
  }

  private def insert0(snippetId: SnippetId, inputs: Inputs): Future[SnippetId] =
    insert(snippetId, inputs).map(_ => snippetId)

  def close(): Unit = ()
}
