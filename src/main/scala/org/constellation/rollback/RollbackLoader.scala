package org.constellation.rollback

import better.files.File
import cats.data.EitherT
import cats.effect.Concurrent
import cats.implicits._
import org.constellation.consensus.StoredSnapshot
import org.constellation.domain.snapshot.{SnapshotInfo, SnapshotInfoStorage, SnapshotStorage}
import org.constellation.primitives.Schema.GenesisObservation
import org.constellation.serializer.KryoSerializer

class RollbackLoader[F[_]](
  snapshotInfoStorage: SnapshotInfoStorage[F],
  snapshotStorage: SnapshotStorage[F],
  genesisObservationPath: File
)(implicit F: Concurrent[F]) {

  def loadSnapshotsFromFile(): EitherT[F, RollbackException, List[(StoredSnapshot, SnapshotInfo)]] =
    for {
      hashes <- snapshotStorage.getSnapshotHashes.attemptT
        .leftMap(_ => CannotLoadSnapshotHashes)
        .leftWiden[RollbackException]

      snapshots <- hashes.traverse { hash =>
        snapshotStorage
          .readSnapshot(hash)
          .product(snapshotInfoStorage.readSnapshotInfo(hash))
          .leftMap(_ => CannotLoadSnapshot(hash))
          .leftWiden[RollbackException]
      }
    } yield snapshots

  def loadGenesisObservation(): EitherT[F, RollbackException, GenesisObservation] =
    F.delay { genesisObservationPath }.flatMap { go =>
      F.delay {
        go.byteArray
      }
    }.flatMap { a =>
      F.delay {
        KryoSerializer.deserializeCast[GenesisObservation](a)
      }
    }.attemptT
      .leftMap(_ => CannotLoadGenesisObservationFile(genesisObservationPath.pathAsString))
}
