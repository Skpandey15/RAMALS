package io.ramals.learningplatform.assessment;

import java.util.UUID;

/** One item of an assembled form: which item, where it is shown, and why it was chosen. */
public record SelectedItem(
    UUID itemVersionId,
    int presentationOrder,
    SelectionReason reason) {
}
