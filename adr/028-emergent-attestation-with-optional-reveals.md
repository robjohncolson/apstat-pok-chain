# ADR-028: Emergent Attestation with Optional Reveals

**Date:** 2025-01-27  
**Status:** Accepted  
**Context:** V2 Development Phase - Blockchain-Powered Student Experience  

## Context

Building on the foundational work of [ADR-012: Social Consensus and Proof of Knowledge](./012-social-consensus-and-proof-of-knowledge.md) and [ADR-026: Granular Progress Transactions for PoK](./026-granular-progress-transactions-for-pok.md), we need to implement an emergent attestation system that enables true decentralized consensus without relying on authoritative answer keys.

The current attestation system requires peers to verify answers using a hidden correct answer, which creates a centralized truth dependency. Drawing inspiration from the Claude brainstorming sessions documented in [`docs/refactor/claude_4_opus_thoughts.txt`](../refactor/claude_4_opus_thoughts.txt), we recognize that "the correct answer will be.. whatever the students agree it to be" through natural convergence over time.

This emergent consensus model aligns with statistical principles where truth emerges from collective intelligence rather than authority. Students will naturally converge on the best answers through repeated attempts, creating a self-organizing knowledge discovery system.

## Decision

We will implement an **Emergent Attestation System** with the following core components:

### Attestation Transaction Structure

```typescript
interface AttestationTransaction {
  type: 'attestation';
  questionId: string;
  answerHash?: string;      // For MCQs - SHA-256 hash of selected option
  answerText?: string;      // For FRQs - text-based scoring (1-5 scale)
  attesterPubKey: string;
  signature: string;
  timestamp: number;
}
```

### Emergent Consensus via Distributions

The system tracks answer distributions over time to identify convergence patterns:

```typescript
interface QuestionDistribution {
  questionId: string;
  totalAttestations: number;
  mcqDistribution?: {
    A: number;
    B: number; 
    C: number;
    D: number;
    E?: number;
  };
  frqDistribution?: {
    scores: number[];        // Array of 1-5 scores
    averageScore: number;
    standardDeviation: number;
  };
  convergenceScore: number;  // Percentage of highest consensus option
  lastUpdated: number;
}
```

### Quorum Requirements

- **Minimum Quorum**: 3 attestations required before any consensus calculation
- **Progressive Confidence**: Higher quorum requirements for block finality based on convergence score
  - Low convergence (<50%): Requires 5+ attestations
  - Medium convergence (50-80%): Requires 4+ attestations  
  - High convergence (>80%): Requires 3+ attestations

### Optional AP Reveal System

Post-50% convergence, students may submit anonymous "AP Reveal" transactions:

```typescript
interface APRevealTransaction {
  type: 'ap_reveal';
  questionId: string;
  officialAnswer: string;
  confidence: number;       // 1-5 scale of confidence
  anonymousSignature: string; // One-time key signature
  timestamp: number;
}
```

These reveals serve as gentle course corrections but do not override emergent consensus.

## Question Type Handling

### Multiple Choice Questions (MCQs)
- **Hash-based Voting**: Students submit SHA-256 hashes of their selected option (A, B, C, D, E)
- **Distribution Tracking**: System maintains counts for each option
- **Convergence Metric**: Highest percentage option becomes consensus answer
- **Anti-Gaming**: Hash prevents immediate answer revelation while enabling verification

### Free Response Questions (FRQs)  
- **Text-based Rubric Scoring**: Students submit 1-5 scale scores based on rubric criteria
- **Peer Review Process**: Multiple students score the same response
- **Statistical Convergence**: Average score with confidence intervals
- **Qualitative Feedback**: Optional text feedback for improvement

## Social and Motivational Benefits

### Peer Learning Through Attestation
- **Increased Engagement**: Students actively participate in peer assessment
- **Knowledge Reinforcement**: Attestation requires understanding the material
- **Social Accountability**: Peer verification creates natural quality pressure
- **Distributed Practice**: 89 lessons × 3 attestations = 267 additional practice opportunities

### Gamification Elements
- **Reputation System**: Track attestation accuracy over time
- **Consensus Rewards**: Bonus points for early correct convergence
- **Knowledge Markets**: Students trade attestations based on expertise areas
- **Study Group Formation**: Natural clustering around attestation partnerships

### Statistical Education
- **Living Statistics**: Students experience convergence, distributions, and confidence intervals
- **Data Visualization**: Real-time charts showing answer distributions
- **Hypothesis Testing**: Students can observe how sample size affects confidence
- **Meta-Learning**: Understanding how collective intelligence emerges

## Implementation Strategy

### Phase 1: Core Attestation Engine
- Implement attestation transaction creation and validation
- Build distribution tracking and convergence calculation
- Create quorum verification logic
- Test with MCQ hash-based voting

### Phase 2: FRQ Scoring System
- Implement text-based rubric scoring
- Add statistical analysis for FRQ distributions
- Create peer review workflow
- Test convergence with 1-5 scale responses

### Phase 3: Optional AP Reveal Integration
- Add anonymous AP reveal transaction support
- Implement confidence-weighted adjustments
- Create teacher dashboard for reveal monitoring
- Test reveal impact on convergence patterns

## Technical Specifications

### Convergence Calculation
```typescript
function calculateConvergence(distribution: QuestionDistribution): number {
  if (distribution.mcqDistribution) {
    const values = Object.values(distribution.mcqDistribution);
    const max = Math.max(...values);
    return max / distribution.totalAttestations;
  }
  
  if (distribution.frqDistribution) {
    // Standard deviation-based convergence for FRQs
    const cv = distribution.frqDistribution.standardDeviation / 
               distribution.frqDistribution.averageScore;
    return Math.max(0, 1 - cv); // Lower CV = higher convergence
  }
  
  return 0;
}
```

### Anti-Gaming Measures
- **Time-based Rate Limiting**: Cannot attest same question within 30 days
- **Consistency Tracking**: Monitor flip-flopping on identical questions
- **Reputation Weighting**: Higher reputation attesters carry more weight
- **Outlier Detection**: Flag unusual voting patterns for review

## Benefits

### Educational Value
- **Authentic Assessment**: Students engage with real statistical concepts
- **Peer Learning**: Attestation process reinforces understanding
- **Critical Thinking**: Students must justify their reasoning
- **Collective Intelligence**: Experience how knowledge emerges from groups

### Technical Advantages
- **True Decentralization**: No authoritative answer keys required
- **Resilient Consensus**: System continues functioning with network partitions
- **Scalable Verification**: Peer attestation scales with student population
- **Evolutionary Improvement**: System learns and improves over time

### Pedagogical Innovation
- **No-Authority Learning**: Students become active participants in knowledge creation
- **Statistical Literacy**: Direct experience with convergence and distributions
- **Social Learning**: Peer interaction enhances retention and understanding
- **Motivation Through Agency**: Students feel ownership of the learning process

## Risks and Mitigations

**Risk**: Early wrong consensus could persist  
**Mitigation**: AP reveal system provides gentle course correction, time-weighted attestations allow evolution

**Risk**: Collusion or gaming attempts  
**Mitigation**: Reputation system, rate limiting, and statistical outlier detection

**Risk**: Low participation reduces consensus quality  
**Mitigation**: Gamification elements and social pressure encourage participation

**Risk**: Complex questions might not converge  
**Mitigation**: Accept ambiguity as educational opportunity, flag low-convergence questions

## References

- [ADR-012: Social Consensus and Proof of Knowledge](./012-social-consensus-and-proof-of-knowledge.md)
- [ADR-026: Granular Progress Transactions for PoK](./026-granular-progress-transactions-for-pok.md)
- [Claude Brainstorming Session](../refactor/claude_4_opus_thoughts.txt) - "No-authority emergent consensus model"
- [Statistical Convergence Theory](https://en.wikipedia.org/wiki/Convergence_of_random_variables)
- [Collective Intelligence Research](https://en.wikipedia.org/wiki/Collective_intelligence)

## Success Criteria

- **Convergence Rate**: 80%+ of questions achieve >70% consensus within 10 attestations
- **Participation Level**: Average of 4+ attestations per question across curriculum
- **Learning Outcomes**: Students demonstrate improved statistical reasoning on assessments
- **System Resilience**: Consensus continues functioning during network partitions
- **Social Engagement**: Students report increased motivation and peer interaction

---

This ADR establishes the foundation for a truly decentralized educational assessment system where knowledge emerges through collective intelligence rather than authoritative decree. The emergent attestation system transforms students from passive consumers to active participants in the knowledge creation process. 