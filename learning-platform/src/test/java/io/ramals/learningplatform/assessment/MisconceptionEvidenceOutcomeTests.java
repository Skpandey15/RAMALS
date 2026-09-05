package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Granular diagnostic ontology foundation (M2-ADR-026): the governed V1 truth table for
 * {@link MisconceptionEvidenceOutcome#classify}, a deliberately separate rule from
 * {@link HypothesisEvidenceOutcome#classify} -- never modified, overloaded, or reused.
 */
class MisconceptionEvidenceOutcomeTests {

  @Test
  void aCorrectAnswerOnAnEligibleItemIsContradictory() {
    assertThat(MisconceptionEvidenceOutcome.classify(true, false))
        .isEqualTo(MisconceptionEvidenceOutcome.CONTRADICTORY);
    // Whether the (irrelevant, since correct) selected option happens to be tagged makes no
    // difference -- a correct answer is never itself tagged to any misconception (DB invariant),
    // but the classifier's own rule does not even need to know that to still answer correctly.
    assertThat(MisconceptionEvidenceOutcome.classify(true, true))
        .isEqualTo(MisconceptionEvidenceOutcome.CONTRADICTORY);
  }

  @Test
  void anIncorrectAnswerTaggedToTheMisconceptionUnderTestIsSupporting() {
    assertThat(MisconceptionEvidenceOutcome.classify(false, true))
        .isEqualTo(MisconceptionEvidenceOutcome.SUPPORTING);
  }

  @Test
  void anIncorrectAnswerNotTaggedToTheMisconceptionUnderTestIsInconclusive() {
    // Wrong for a different, untagged, or differently-tagged reason -- neither confirms nor
    // refutes the misconception under test.
    assertThat(MisconceptionEvidenceOutcome.classify(false, false))
        .isEqualTo(MisconceptionEvidenceOutcome.INCONCLUSIVE);
  }

  @Test
  void everyGovernedTruthTableRowClassifiesExactly() {
    assertThat(MisconceptionEvidenceOutcome.classify(true, false))
        .isEqualTo(MisconceptionEvidenceOutcome.CONTRADICTORY);
    assertThat(MisconceptionEvidenceOutcome.classify(false, true))
        .isEqualTo(MisconceptionEvidenceOutcome.SUPPORTING);
    assertThat(MisconceptionEvidenceOutcome.classify(false, false))
        .isEqualTo(MisconceptionEvidenceOutcome.INCONCLUSIVE);
  }
}
