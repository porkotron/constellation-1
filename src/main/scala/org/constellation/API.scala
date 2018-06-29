package org.constellation

import java.net.InetSocketAddress
import java.security.{KeyPair, PublicKey}

import akka.actor.ActorRef
import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{entity, path, _}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, PredefinedFromEntityUnmarshallers}
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.scalalogging.Logger
import constellation._
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import org.constellation.crypto.Wallet
import org.constellation.primitives.Schema._
import org.constellation.primitives.Schema
import org.constellation.util.ServeUI
import org.json4s.native
import org.json4s.native.Serialization

import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success, Try}

class API(
           val peerToPeerActor: ActorRef,
           consensusActor: ActorRef,
           udpAddress: InetSocketAddress,
           val data: Data = null,
           val jsPrefix: String = "./ui/target/scala-2.11/ui"
         )
         (implicit executionContext: ExecutionContext, val timeout: Timeout) extends Json4sSupport
  with Wallet
  with ServeUI {

  import data._

  implicit val serialization: Serialization.type = native.Serialization

  implicit val stringUnmarshaller: FromEntityUnmarshaller[String] =
    PredefinedFromEntityUnmarshallers.stringUnmarshaller

  val logger = Logger(s"APIInterface")

  val routes: Route = cors() {
    get {
      path("restart") {
        data.restartNode()
        complete(StatusCodes.OK)
      } ~
      path("submitTX") {
        parameter('address, 'amount) { (address, amount) =>
          logger.error(s"SubmitTX : $address $amount")
          handleSendRequest(SendToAddress(address, amount.toLong))
        }
      } ~
      pathPrefix("address") {
        get {
          extractUnmatchedPath { p =>
            logger.debug(s"Unmatched path on address result $p")
            val ps = p.toString().tail
            val balance = validLedger.getOrElse(ps, 0).toString
            complete(s"Balance: $balance")
          }
        }
      } ~
      pathPrefix("txHash") { // TODO: Rename to transaction
        get {
          extractUnmatchedPath { p =>
            logger.debug(s"Unmatched path on address result $p")
            val ps = p.toString().tail
            complete(db.getAs[TX](ps).json)
          }
        }
      } ~
      path("longestChain") {
        val ancestors = extractBundleAncestorsUntilValidation(bestBundle)
        val all = validBundles ++ ancestors.map{bundleHashToBundle} ++ Seq(bestBundle)
        complete(
          all.json
        )
      } ~
      pathPrefix("bundle") {
        get {
          extractUnmatchedPath { p =>
            logger.debug(s"Unmatched path on bundle result $p")
            val ps = p.toString().tail
            complete(validBundles.filter{_.hash == ps}.headOption.prettyJson)
          }
        }
      } ~
      path("setKeyPair") {
        parameter('keyPair) { kpp =>
          logger.debug("Set key pair " + kpp)
          val res = if (kpp.length > 10) {
            val rr = Try {
              data.updateKeyPair(kpp.x[KeyPair])
              StatusCodes.OK
            }.getOrElse(StatusCodes.BadRequest)
            rr
          } else StatusCodes.BadRequest
          complete(res)
        }
      } ~
        path("metrics") {
          complete(Metrics(Map(
            "address" -> selfAddress.address,
            "balance" -> (selfIdBalance.getOrElse(0L) / Schema.NormalizationFactor).toString,
            "id" -> id.b58,
            "z_keyPair" -> keyPair.json,
            "shortId" -> id.short,
            "last1000BundleHashSize" -> last100BundleHashes.size.toString,
            "numSyncedTX" -> numSyncedTX.toString,
            "numSyncedBundles" -> numSyncedBundles.toString,
            "numValidBundles" -> totalNumValidBundles.toString,
            "numValidTransactions" -> totalNumValidatedTX.toString,
            "memPoolSize" -> memPool.size.toString,
            "totalNumBroadcasts" -> totalNumBroadcastMessages.toString,
            "totalNumBundleMessages" -> totalNumBundleMessages.toString,
            "lastConfirmationUpdateTime" -> lastConfirmationUpdateTime.toString,
            "numPeers" -> peers.size.toString,
            "peers" -> peers.map{ z =>
              val addr = s"http://${z.data.apiAddress.getHostString}:${z.data.apiAddress.getPort}"
              s"${z.data.id.short} API: $addr "
            }.mkString(" --- "),
            "z_genesisBundleHash" -> Option(genesisBundle).map{_.hash}.getOrElse("N/A"),
         //   "bestBundleCandidateHashes" -> bestBundleCandidateHashes.map{_.hash}.mkString(","),
            "numActiveBundles" -> activeDAGBundles.size.toString,
            "last10TXHash" -> last100SelfSentTransactions.reverse.slice(0, 10).map{_.hash}.mkString(","),
            "last10ValidBundleHashes" -> last100BundleHashes.reverse.slice(0, 10).reverse.mkString(","),
            "lastValidBundleHash" -> lastBundleHash.pbHash,
            "lastValidBundle" -> Try{Option(lastBundle).map{_.pretty}.getOrElse("")}.getOrElse(""),
            "z_genesisBundle" -> Option(genesisBundle).map(_.json).getOrElse(""),
            "z_genesisBundleIds" -> Option(genesisBundle).map(_.extractIds).mkString(", "),
            "selfBestBundle" -> Option(bestBundle).map{_.pretty}.getOrElse(""),
            "reputations" -> normalizedDeterministicReputation.map{
              case (k,v) => k.short + " " + v
            }.mkString(" - "),
            "numProcessedBundles" -> bundleHashToBundle.size.toString,
            "numSyncPendingBundles" -> syncPendingBundleHashes.size.toString,
            "numSyncPendingTX" -> syncPendingTXHashes.size.toString,
            "peerBestBundles" -> peerSync.toMap.map{
              case (id, b) =>
                s"PEER: ${id.short}, BEST: ${b.bestBundle.map{ z => z.short + " " +
                  Try{z.pretty}.getOrElse("unknown")}.getOrElse("")} " // LAST: ${b.lastBestBundle.pretty}
            }.mkString(" ----- "),
            "allPeerSynchronizedLastHash" -> Try(
              (peerSync.map{_._2.validBundleHashes.last} ++ Seq(lastBundle.hash)).toSet.size == 1
              ).map{_.toString}.getOrElse(""),
            "allPeerAllBundleHashSync" -> peerSync.forall{_._2.validBundleHashes == last100BundleHashes}.toString,
            "z_peers" -> peers.map{_.data}.json,
            "z_UTXO" -> validLedger.toMap.json,
            "z_Bundles" -> activeDAGBundles.map{_.pretty}.mkString(" - - - "),
            "downloadMode" -> downloadMode.toString,
            "allPeersHaveKnownBestBundles" -> peerSync.forall{
              case (_, hb) =>
                hb.bestBundle.forall{ b => bundleHashToBundle.contains(b.hash)}
            }.toString,
            "allPeersAgreeWithBestBundle" -> peerSync.forall{
              _._2.bestBundle.exists{_.hash == bestBundle.hash}
            }.toString
            //,
            // "z_lastBundleVisualJSON" -> Option(lastBundle).map{ b => b.extractTreeVisual.json}.getOrElse("")
          )))
        } ~
        path("validTX") {
          complete(last1000ValidTX)
        } ~
        path("makeKeyPair") {
          val pair = constellation.makeKeyPair()
          wallet :+= pair
          complete(pair)
        } ~
        path("genesis" / LongNumber) { numCoins =>
          val ret = if (genesisBundle == null) {
            val debtAddress = walletPair
            val tx = createTransaction(selfAddress.address, numCoins, src = debtAddress.address.address)
            createGenesis(tx)
            tx
          } else genesisBundle.extractTX.head
          complete(ret)
        } ~
        path("makeKeyPairs" / IntNumber) { numPairs =>
          val pair = Seq.fill(numPairs){constellation.makeKeyPair()}
          wallet ++= pair
          complete(pair)
        } ~
        path("wallet") {
          complete(wallet)
        } ~
        path("selfAddress") {
          //  val pair = constellation.makeKeyPair()
          //  wallet :+= pair
          //  complete(constellation.pubKeyToAddress(pair.getPublic))
          complete(id.address)
        } ~
        path("id") {
          complete(id)
        } ~
        path("nodeKeyPair") {
          complete(keyPair)
        } ~
        // TODO: revisit
        path("health") {
          complete(StatusCodes.OK)
        } ~
        path("peers") {
          complete(Peers(peerIPs.toSeq))
        } ~
        path("peerids") {
          complete(peers.map{_.data})
        } ~
        path("actorPath") {
          complete(peerToPeerActor.path.toSerializationFormat)
        } ~
        path("balance") {
          entity(as[PublicKey]) { account =>
            logger.debug(s"Received request to query account $account balance")

            // TODO: update balance
            // complete((chainStateActor ? GetBalance(account)).mapTo[Balance])

            complete(StatusCodes.OK)
          }
        } ~
        path("dashboard") {
          val bundleSubset = validBundles.take(20)

          val transactions: Set[TX] = bundleSubset.flatMap(b => b.extractTX).sortBy(_.txData.time).toSet

          complete(Map(
            "peers" -> peers.map{_.data},
            "transactions" -> transactions
          ))
        } ~
        jsRequest ~
        serveMainPage
    } ~
      post {
        path ("sendToAddress") {
          entity(as[SendToAddress]) { s =>
            handleSendRequest(s)
          }
        } ~
          path("db") {
            entity(as[String]){ e: String =>
              import constellation.EasyFutureBlock
              val cleanStr = e.replaceAll('"'.toString, "")
              val res = db.get(cleanStr)
              complete(res)
            }
          } ~
          path ("tx") {
            entity(as[TX]) { tx =>
              peerToPeerActor ! tx
              complete(StatusCodes.OK)
            }
          } ~
          path("peer") {
            entity(as[String]) { peerAddress =>

              Try {
                //    logger.debug(s"Received request to add a new peer $peerAddress")
                val result = Try {
                  peerAddress.replaceAll('"'.toString, "").split(":") match {
                    case Array(ip, port) => new InetSocketAddress(ip, port.toInt)
                    case a@_ => logger.debug(s"Unmatched Array: $a"); throw new RuntimeException(s"Bad Match: $a");
                  }
                }.toOption match {
                  case None =>
                    StatusCodes.BadRequest
                  case Some(v) =>
                    val fut = (peerToPeerActor ? AddPeerFromLocal(v)).mapTo[StatusCode]
                    val res = Try {
                      Await.result(fut, timeout.duration)
                    }.toOption
                    res match {
                      case None =>
                        StatusCodes.RequestTimeout
                      case Some(f) =>
                        if (f == StatusCodes.Accepted) {
                          var attempts = 0
                          var peerAdded = false
                          while (attempts < 5) {
                            attempts += 1
                            Thread.sleep(1500)
                            //peerAdded = peers.exists(p => v == p.data.externalAddress)
                            peerAdded = peerLookup.contains(v)
                          }
                          if (peerAdded) StatusCodes.OK else StatusCodes.NetworkConnectTimeout
                        } else f
                    }
                }

                logger.debug(s"New peer request $peerAddress statusCode: $result")
                result
              } match {
                case Failure(e) => e.printStackTrace()
                  complete(StatusCodes.InternalServerError)
                case Success(x) => complete(x)
              }
            }
          } ~
          path("ip") {
            entity(as[String]) { externalIp =>
              var ipp : String = ""
              val addr = externalIp.replaceAll('"'.toString,"").split(":") match {
                case Array(ip, port) =>
                  ipp = ip
                  externalHostString = ip
                  new InetSocketAddress(ip, port.toInt)
                case a@_ => { logger.debug(s"Unmatched Array: $a"); throw new RuntimeException(s"Bad Match: $a"); }
              }
              logger.debug(s"Set external IP RPC request $externalIp $addr")
              data.externalAddress = addr
              if (ipp.nonEmpty) data.apiAddress = new InetSocketAddress(ipp, 9000)
              complete(StatusCodes.OK)
            }
          } ~
          path("reputation") {
            entity(as[Seq[UpdateReputation]]) { ur =>
              secretReputation = ur.flatMap{ r => r.secretReputation.map{id -> _}}.toMap
              publicReputation = ur.flatMap{ r => r.publicReputation.map{id -> _}}.toMap
              complete(StatusCodes.OK)
            }
          }
      }
  }
}