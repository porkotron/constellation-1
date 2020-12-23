package org.constellation.gossip.snapshot

import java.security.KeyPair

import cats.Parallel
import cats.effect.{Concurrent, Timer}
import cats.syntax.all._
import org.constellation.domain.redownload.RedownloadService.SnapshotProposalsAtHeight
import org.constellation.gossip.GossipService
import org.constellation.gossip.sampling.PeerSampling
import org.constellation.gossip.state.{GossipMessage, GossipMessagePathTracker}
import org.constellation.infrastructure.p2p.PeerResponse.PeerClientMetadata
import org.constellation.infrastructure.p2p.{ClientInterpreter, PeerResponse}
import org.constellation.p2p.Cluster
import org.constellation.schema.Id
import org.constellation.schema.signature.Signed
import org.constellation.schema.snapshot.SnapshotProposal

class SnapshotProposalGossipService[F[_]: Concurrent: Timer: Parallel](
  selfId: Id,
  keyPair: KeyPair,
  peerSampling: PeerSampling[F],
  cluster: Cluster[F],
  apiClient: ClientInterpreter[F]
) extends GossipService[F, Signed[SnapshotProposal]](
      selfId,
      keyPair,
      peerSampling,
      cluster,
      new GossipMessagePathTracker[F, Signed[SnapshotProposal]]
    ) {

  override protected def spreadFn(
    nextClientMetadata: PeerResponse.PeerClientMetadata,
    message: GossipMessage[Signed[SnapshotProposal]]
  ): F[Unit] = apiClient.snapshot.postPeerProposal(message)(nextClientMetadata)

  override protected def validationFn(
    peerClientMetadata: PeerClientMetadata,
    message: GossipMessage[Signed[SnapshotProposal]]
  ): F[Boolean] =
    for {
      proposals: Option[SnapshotProposalsAtHeight] <- apiClient.snapshot.getPeerProposals(peerClientMetadata.id)(
        peerClientMetadata
      )
      expectedHeight = message.data.value.height
      expectedHash = message.data.value.hash
      validProposalAtHeight = proposals
        .flatMap(_.get(expectedHeight))
        .exists(_.value.hash == expectedHash)
    } yield validProposalAtHeight
}

object SnapshotProposalGossipService {

  def apply[F[_]: Concurrent: Timer: Parallel](
    selfId: Id,
    keyPair: KeyPair,
    peerSampling: PeerSampling[F],
    cluster: Cluster[F],
    apiClient: ClientInterpreter[F]
  ): SnapshotProposalGossipService[F] =
    new SnapshotProposalGossipService(selfId, keyPair, peerSampling, cluster, apiClient)
}
