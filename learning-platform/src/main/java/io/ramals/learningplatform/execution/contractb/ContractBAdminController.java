package io.ramals.learningplatform.execution.contractb;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The deliberate, operator-only way to commission a Contract B execution.
 *
 * <p><strong>Not a learner-facing route and not an unauthenticated one.</strong> It sits under
 * {@code /api/v1/admin} behind {@code ROLE_ADMIN}, the same gate as content administration, because
 * commissioning a durable execution spends real money at a paid provider and starts work that
 * outlives the request. Nobody should reach it by accident.
 *
 * <p><strong>It does not exist unless Contract B is switched on.</strong> The condition is on the
 * bean, not inside the method: with {@code ramals.contract-b.enabled=false} there is no controller,
 * no mapping, and the path 404s exactly as an unknown path does. A route that existed and refused
 * would advertise the feature and give an attacker something to probe; a route that is absent gives
 * them nothing, and gives a reviewer one fact to check rather than a code path to reason about.
 *
 * <p>The response reports whatever state the lifecycle reached, including
 * {@code UNKNOWN_TERMINAL}. That is not an error and is not reported as one: an ambiguous submission
 * is a real, expected outcome of a provider with no replay-safe admission, and dressing it as a 5xx
 * would invite the caller to retry — which is the one thing that must never follow it.
 */
@RestController
@RequestMapping("/api/v1/admin/contract-b/executions")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(prefix = "ramals.contract-b", name = "enabled", havingValue = "true")
@Validated
public class ContractBAdminController {

  private final ContractBCommissioningService commissioning;

  public ContractBAdminController(ContractBCommissioningService commissioning) {
    this.commissioning = commissioning;
  }

  /**
   * Commissions exactly one execution.
   *
   * <p>Accepts what the execution should ask for, and nothing that identifies it. The request id and
   * the idempotency key are server-derived, because the Definition of Done requires the
   * {@code custom_id} the provider sees to be server-derived — correlation a caller can choose is
   * correlation that can be aimed at another execution.
   *
   * <p>{@code @Valid} is on the parameter and has to be. A class-level {@code @Validated} enables
   * method validation for constraints on the parameters themselves; it does not descend into a
   * request body's fields, so without this annotation every constraint on {@link CommissionRequest}
   * is decorative — the bounds would be documented and unenforced, which is worse than absent
   * because a reader would believe them.
   */
  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  CommissionResponse commission(@Valid @RequestBody CommissionRequest request) {
    ContractBCommissioningService.Commissioned commissioned = commissioning.commission(
        request.model(), request.modelRoute(), request.prompt(), request.maxOutputTokens());
    return new CommissionResponse(commissioned.requestId(), commissioned.state().name());
  }

  /** What to ask the provider for. Carries no identity: those are derived server-side. */
  record CommissionRequest(
      @NotBlank @Size(max = 128) String model,
      @NotBlank @Size(max = 64) String modelRoute,
      @NotBlank @Size(max = 8_000) String prompt,
      @Positive @Max(64_000) int maxOutputTokens) {}

  /**
   * The durable identity and the state reached.
   *
   * <p>Returns the {@code requestId} rather than the provider's execution id, deliberately: the
   * RAMALS identity is the one an operator can use against the ledger and the runbook, and it exists
   * even when a submission ended ambiguously and no provider identity was ever learned.
   */
  record CommissionResponse(String requestId, String state) {}
}
