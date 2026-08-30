package io.ramals.learningplatform.registration;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one unauthenticated route in the platform.
 *
 * <p>{@code SecurityConfig} permits exactly {@code POST /api/v1/registration} and leaves
 * {@code anyRequest().authenticated()} in force for everything else, so widening this surface takes a
 * deliberate edit in a file whose tests assert the shape of the chain.
 * {@link PreAuthRegistrationRateLimitFilter} runs ahead of authentication for this path, because the
 * platform's existing limiter is keyed on the authenticated subject and there is no subject here.
 *
 * <p><strong>202, not 201.</strong> The response is an acknowledgement, not a created resource: the
 * account is not usable until the learner verifies the email Keycloak sends. Returning 201 with a
 * location would imply a resource the caller can go and use.
 *
 * <p>The response body carries the operation id and the next step, and nothing about whether the
 * email was already registered — see {@code RegistrationService} for why that silence is deliberate.
 */
@RestController
@RequestMapping("/api/v1/registration")
class RegistrationController {

  private final RegistrationService registrations;

  RegistrationController(RegistrationService registrations) {
    this.registrations = registrations;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  RegistrationService.RegistrationResponse register(
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody RegistrationRequest request) {
    // Declared non-required so a missing header becomes the domain's own bounded-key rejection
    // rather than a framework 400 that bypasses the audit trail and the metric.
    return registrations.register(idempotencyKey, request);
  }
}
