package org.constellation.p2p

import cats.data.ValidatedNel
import cats.effect.{Blocker, Concurrent, ContextShift, Sync}
import cats.syntax.all._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.constellation.infrastructure.endpoints.BuildInfoEndpoints.BuildInfoJson
import org.constellation.infrastructure.p2p.{ClientInterpreter, PeerResponse}
import org.constellation.infrastructure.p2p.PeerResponse.PeerClientMetadata

class JoiningPeerValidator[F[_]: Concurrent](apiClient: ClientInterpreter[F], unboundedBlocker: Blocker)(
  implicit val CS: ContextShift[F]
) {

  private val logger = Slf4jLogger.getLogger[F]

  private type ValidationResult[A] = ValidatedNel[JoiningPeerValidationMessage, A]

  def isValid(peerClientMetadata: PeerClientMetadata): F[Boolean] =
    validation(peerClientMetadata).map(_.isValid)

  def validation(peerClientMetadata: PeerClientMetadata): F[ValidationResult[String]] =
    validateBuildInfo(peerClientMetadata)

  private def validateBuildInfo(peerClientMetadata: PeerClientMetadata): F[ValidationResult[String]] = {
    val validate: F[ValidationResult[String]] = for {
      peerBuildInfo <- PeerResponse.run(apiClient.buildInfo.getBuildInfo(), unboundedBlocker)(peerClientMetadata)
      _ <- logger.debug(s"BuildInfo (peer): ${peerBuildInfo}")

      buildInfo = BuildInfoJson()
      _ <- logger.debug(s"BuildInfo (node): ${buildInfo}")

      isValid = peerBuildInfo == buildInfo
    } yield
      if (!isValid) JoiningPeerHasDifferentVersion(peerClientMetadata.host).invalidNel
      else peerClientMetadata.host.validNel

    validate.handleErrorWith(
      error =>
        logger.info(s"Cannot get build info of joining peer : ${peerClientMetadata.host} : $error") >>
          Sync[F].delay(JoiningPeerUnavailable(peerClientMetadata.host).invalidNel)
    )
  }
}

object JoiningPeerValidator {

  def apply[F[_]: Concurrent](apiClient: ClientInterpreter[F], unboundedBlocker: Blocker)(
    implicit CS: ContextShift[F]
  ): JoiningPeerValidator[F] =
    new JoiningPeerValidator(apiClient, unboundedBlocker)
}
