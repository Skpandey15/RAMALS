package io.ramals.learningplatform.assessment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** Learner-submitted diagnostic responses. Shape is validated here; semantics in the service. */
public record DiagnosticSubmissionRequest(
    @NotEmpty @Valid List<ItemResponse> responses) {

  public record ItemResponse(
      @NotBlank String itemId,
      @NotEmpty List<@NotBlank String> selectedOptions) {
  }
}
