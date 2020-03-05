package org.constellation.rollback

trait RollbackException extends Throwable

case class CannotLoadGenesisObservationFile(path: String) extends RollbackException

case class CannotLoadSnapshot(hash: String) extends RollbackException

object CannotLoadSnapshotHashes extends RollbackException

object CannotWriteToDisk extends RollbackException

object InvalidBalances extends RollbackException

object CannotCalculate extends RollbackException
