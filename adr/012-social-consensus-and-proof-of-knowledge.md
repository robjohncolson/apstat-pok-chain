# 012. Social Consensus and Proof of Knowledge

- **Status:** Accepted
- **Date:** 6/27/2025

## Context

The previous Proof of Knowledge model required a secure, hidden answer key to verify a miner's puzzle solution. This is complex to manage and represents a form of centralized truth.

## Decision

We will adopt a "Social Consensus" model. A block's validity will be determined by peer attestation, not a secret answer key.

1. A "Miner" proposes a *candidate block* containing their answer to a PoK puzzle.
2. "Attesters" (other peers) receive this candidate block, solve the same puzzle independently, and if their answer matches the miner's, they sign and broadcast a new `ATTESTATION` transaction.
3. A candidate block becomes final only after collecting a "quorum" of valid attestations (e.g., 30% of active peers, min 3). The final block will include these attestations as proof of consensus.

## Consequences

**Positive:**
- Eliminates the need for a secure answer key, making the system truly decentralized
- Encourages critical thinking and peer review, as students must be confident in their answers to reach consensus

**Negative:**
- Increases P2P network traffic due to the new `ATTESTATION` messages
- Introduces a new challenge: The UI must now manage the state of candidate blocks and provide an interface for attestation 