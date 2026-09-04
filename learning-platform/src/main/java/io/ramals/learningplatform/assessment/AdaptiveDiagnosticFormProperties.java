package io.ramals.learningplatform.assessment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the adaptive (DIAGNOSTIC_SELECTION_V2) form is composed.
 *
 * <p>The defaults are the approved transitional packet: 5 SINGLE_CHOICE + 2 FILL_BLANK, 7 items
 * total. SHORT_ANSWER and USE_CASE have no quota here at all -- they are not selectable content
 * until M2-ADR-022's evaluation boundary exists, and {@link AssessmentRepository}'s scoreable-type
 * filter keeps them out of the pool this composes from regardless of what this class configures.
 */
@ConfigurationProperties("ramals.diagnostic.adaptive-form")
public class AdaptiveDiagnosticFormProperties {

  private int singleChoiceTarget = 5;

  private int fillBlankTarget = 2;

  public int getSingleChoiceTarget() {
    return singleChoiceTarget;
  }

  public void setSingleChoiceTarget(int singleChoiceTarget) {
    this.singleChoiceTarget = singleChoiceTarget;
  }

  public int getFillBlankTarget() {
    return fillBlankTarget;
  }

  public void setFillBlankTarget(int fillBlankTarget) {
    this.fillBlankTarget = fillBlankTarget;
  }
}
