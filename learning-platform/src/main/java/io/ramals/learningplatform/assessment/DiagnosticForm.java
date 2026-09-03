package io.ramals.learningplatform.assessment;

import java.util.List;

/**
 * An assembled diagnostic form, plus the counts that describe how it was assembled.
 *
 * <p>The counts are not decoration. A form that grew past its configured size to keep every skill
 * covered, or one that had to reuse items the learner saw last week because the pool offered
 * nothing else, is still a correct form -- but both are facts an operator should be able to see
 * without reconstructing the selection by hand, so they are logged when the form is persisted.
 */
public record DiagnosticForm(
    List<SelectedItem> items,
    int poolSize,
    int skillsCovered,
    int difficultiesCovered,
    int recentlyPresentedReused) {
}
