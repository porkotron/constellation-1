package org.constellation.schema

import java.security.PublicKey

import com.google.common.hash.Hashing
import org.constellation.keytool.KeyUtils
import org.constellation.keytool.KeyUtils._

case class Id(hex: String) {

  @transient
  val short: String = hex.toString.slice(0, 5)

  @transient
  val medium: String = hex.toString.slice(0, 10)

  @transient
  lazy val address: String = KeyUtils.publicKeyToAddressString(toPublicKey)

  @transient
  lazy val toPublicKey: PublicKey = hexToPublicKey(hex)

  @transient
  lazy val bytes: Array[Byte] = KeyUtils.hex2bytes(hex)

  @transient
  lazy val bigInt: BigInt = BigInt(bytes)

  @transient
  lazy val distance: BigInt = BigInt(Hashing.sha256.hashBytes(toPublicKey.getEncoded).asBytes())
}