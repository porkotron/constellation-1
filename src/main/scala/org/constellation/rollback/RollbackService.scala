package org.constellation.rollback

import cats.data.EitherT
import cats.effect.{Concurrent, ContextShift, Sync}
import cats.implicits._
import cats.syntax._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.constellation.DAO
import org.constellation.consensus.StoredSnapshot
import org.constellation.domain.snapshot.{SnapshotInfo, SnapshotInfoStorage, SnapshotStorage}
import org.constellation.primitives.Genesis
import org.constellation.primitives.Schema.GenesisObservation
import org.constellation.rewards.{RewardSnapshot, RewardsManager}
import org.constellation.storage.SnapshotService
import org.constellation.util.AccountBalances.AccountBalances

class RollbackService[F[_]: Concurrent](
  dao: DAO,
  rollbackBalances: RollbackAccountBalances,
  snapshotService: SnapshotService[F],
  rollbackLoader: RollbackLoader[F],
  rewardsManager: RewardsManager[F]
)(implicit C: ContextShift[F]) {

  val logger = Slf4jLogger.getLogger[F]

  case class RollbackData(
    storedSnapshots: Seq[(StoredSnapshot, SnapshotInfo)],
    genesisObservation: GenesisObservation
  ) {}

  def validateAndRestore(): EitherT[F, RollbackException, Unit] =
    for {
      rollbackData <- validate()
      _ <- restore(rollbackData)
    } yield ()

//  def validate(): EitherT[F, RollbackException, Unit] =
//    for {
////      hashes <- snapshotStorage.getSnapshotHashes.attemptT
//
////      storedSnapshots <- hashes.traverse { hash =>
////        snapshotStorage.readSnapshot(hash).product(snapshotInfoStorage.readSnapshotInfo(hash))
////      }.leftMap(_ => CannotLoadSnapshotsFiles)
//
////      genesisObservation
//
//      //      storedSnapshots <- snapshotStorage.getSnapshotHashes.attemptT.flatMap {
////
////      }
//      //      storedSnapshots <- EitherT.fromEither[F](rollbackLoader.loadSnapshotsFromFile())
//      //      _ <- EitherT.liftF(logger.info("Snapshots files loaded"))
//      //
//      //      genesisObservation <- EitherT.fromEither[F](rollbackLoader.loadGenesisObservation())
//      //      _ <- EitherT.liftF(logger.info("GenesisObservation file loaded"))
//
//      //      balances <- EitherT.fromEither[F](
//      ////        rollbackBalances.calculate(snapshotInfo.snapshot.snapshot.lastSnapshot, snapshots)
//      ////      )
//      //      genesisBalances <- EitherT.fromEither[F](rollbackBalances.calculate(genesisObservation))
//      //      _ <- EitherT.fromEither[F](validateAccountBalance(balances |+| genesisBalances))
//      //      _ <- EitherT.liftF(logger.info("Account balances validated"))
//      //    } yield RollbackData(storedSnapshots, genesisObservation)
//    } yield ()

  private[rollback] def validate(): EitherT[F, RollbackException, RollbackData] =
    for {
      snapshots <- rollbackLoader.loadSnapshotsFromFile()
      genesisObservation <- rollbackLoader.loadGenesisObservation()
    } yield RollbackData(snapshots, genesisObservation)

  private def restore(rollbackData: RollbackData): EitherT[F, RollbackException, Unit] =
    for {
//      _ <- acceptSnapshots(rollbackData.storedSnapshots)
//      _ <- EitherT.liftF(logger.info("Snapshots restored on disk"))

      // TODO: Adopt rollback to redownload and rewards
//      _ <- EitherT.liftF(restoreRewards(rollbackData._1))
//      _ <- EitherT.liftF(logger.info("Rewards restored"))
      _ <- EitherT.liftF(restoreGenesis(rollbackData.genesisObservation))
      _ <- EitherT.liftF(logger.info("GenesisObservation restored"))

//      _ <- EitherT.liftF(restoreSnapshotInfo(rollbackData._2))
//      _ <- EitherT.liftF(logger.info("SnapshotInfo restored"))
    } yield ()

  private def restoreGenesis(genesisObservation: GenesisObservation): F[Unit] = Sync[F].delay {
    Genesis.acceptGenesis(genesisObservation)(dao)
  }

  private def validateAccountBalance(accountBalances: AccountBalances): Either[RollbackException, Unit] =
    accountBalances.count(_._2 < 0) match {
      case 0 => Right(())
      case _ => Left(InvalidBalances)
    }
}
