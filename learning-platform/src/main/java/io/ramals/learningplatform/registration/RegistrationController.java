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
 * <p>{@code SecurityConfig} permits exactly this path and leaves {@code anyRequest().authenticated()}
 * in force elsewhere. {@link PreAuthRegistrationRateLimitFilter} runs ahead of authentication,
 * because the platform's existing limiter keys on a subject this route does not have.
 *
 * <p>202 rather than 201: the response is an acknowledgement, not a resource the caller can use
 * until the learner verifies the email Keycloak sends. The body says nothing about whether the
 * address was already registered - see {@code RegistrationService}.
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
