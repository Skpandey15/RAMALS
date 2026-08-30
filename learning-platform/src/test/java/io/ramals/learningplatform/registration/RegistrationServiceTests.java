package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Behaviour of the registration orchestration, with the provider and the database substituted.
 *
 * <p>The cases that matter most here are the ones about what is <em>not</em> written: a pre-existing
 * identity must not receive the submitted contact data, a replayed operation must not repeat its side
 * effects, and a rejected request must not leave the operation looking successful.
 */
class RegistrationServiceTests {

  private static final String TERMS = "terms-v1";
  private static final String PRIVACY = "privacy-v1";
  private static final String ADULT = "adult-18-v1";
  private static final UUID OPERATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");
  private static final UUID LEARNER_ID = UUID.fromString("01900000-0000-7000-8000-000000000002");

  private RegistrationProperties properties;
  private RegistrationRepository registrations;
  private LearnerRepository learners;
  private IdentityProviderPort identities;
  private AbuseCeiling ceilings;
  private SimpleMeterRegistry meterRegistry;
  private RegistrationService service;

  @BeforeEach
  void setUp() {
    properties = new RegistrationProperties();
    properties.setEnabled(true);
    properties.getConsent().setTermsVersion(TERMS);
    properties.getConsent().setTermsRef("terms/v1");
    properties.getConsent().setPrivacyVersion(PRIVACY);
    properties.getConsent().setPrivacyRef("privacy/v1");
    properties.getConsent().setAdultStatementVersion(ADULT);

    registrations = mock(RegistrationRepository.class);
    learners = mock(LearnerRepository.class);
    identities = mock(IdentityProviderPort.class);
    ceilings = mock(AbuseCeiling.class);
    meterRegistry = new SimpleMeterRegistry();

    when(ceilings.consume(anyString(), anyInt(), anyInt())).thenReturn(true);
    when(registrations.start(anyString(), anyString()))
        .thenAnswer(invocation -> new RegistrationRepository.Operation(
            OPERATION_ID, invocation.getArgument(1), "STARTED", null));
    when(learners.provisionForSubject(anyString()))
        .thenReturn(new Learner(LEARNER_ID, "subject-1", "ACTIVE", Instant.now(), Instant.now()));

    TransactionTemplate transactions = mock(TransactionTemplate.class);
    when(transactions.execute(any())).thenAnswer(invocation ->
        ((TransactionCallback<?>) invocation.getArgument(0))
            .doInTransaction(mock(TransactionStatus.class)));

    service = new RegistrationService(properties, registrations, learners, identities,
        new PhoneNormalizer(), ceilings, meterRegistry, transactions);
  }

  private static RegistrationRequest request() {
    return new RegistrationRequest("Asha", "Iyer", "Asha.Iyer@example.com", "9876543210", "IN",
        "Pune", "correct horse battery", "correct horse battery", TERMS, PRIVACY, ADULT,
        true, true, true);
  }

  private double counter(String outcome) {
    var counter = meterRegistry.find("ramals.registration.attempts").tag("outcome", outcome)
        .counter();
    return counter == null ? 0d : counter.count();
  }

  // -------------------------------------------------------------------------------------------
  // The account-integrity control
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("an identity this operation did not create receives no contact data")
  void doesNotWriteContactDataAgainstAPreExistingIdentity() {
    // The attack: a learner already exists in Keycloak — provisioned just-in-time at first sign-in,
    // so they have no contact row yet — and somebody who merely knows their email address submits a
    // registration for it. If the pre-existing identity were treated as an ordinary success, the
    // insert would succeed against the victim's learner id and would attach the attacker's name and
    // mobile number to the victim's account.
    when(identities.createLearner(anyString(), any()))
        .thenReturn(new IdentityProviderPort.Identity("victim-subject", true, false));

    RegistrationService.RegistrationResponse response = service.register("key-1", request());

    verify(registrations, never()).complete(any(), any(), any());
    verify(identities, never()).sendVerificationEmail(anyString());
    verify(registrations).audit(eq(OPERATION_ID), any(), any(),
        eq("LEARNER_REGISTRATION_DUPLICATE"), eq("REJECTED"), eq("IDENTITY_ALREADY_EXISTS"));
    // The response is indistinguishable from a real registration, so the endpoint does not become
    // an oracle for whether an account exists at a given address.
    assertThat(response.nextStep()).isEqualTo("EMAIL_VERIFICATION");
    assertThat(counter("duplicate")).isEqualTo(1d);
  }

  @Test
  @DisplayName("an identity this operation created is persisted and sent a verification mail")
  void persistsAndRequestsVerificationForANewIdentity() {
    when(identities.createLearner(anyString(), any()))
        .thenReturn(new IdentityProviderPort.Identity("new-subject", false, true));

    RegistrationService.RegistrationResponse response = service.register("key-1", request());

    verify(registrations).identityCreated(OPERATION_ID, "new-subject");
    verify(registrations).complete(eq(OPERATION_ID), eq(LEARNER_ID), any());
    verify(identities).sendVerificationEmail("new-subject");
    verify(registrations).markEmailRequested(OPERATION_ID);
    assertThat(response.nextStep()).isEqualTo("EMAIL_VERIFICATION");
    assertThat(counter("success")).isEqualTo(1d);
  }

  @Test
  @DisplayName("the stored consent record uses server-known versions, never the submitted strings")
  void storesServerKnownConsentVersions() {
    when(identities.createLearner(anyString(), any()))
        .thenReturn(new IdentityProviderPort.Identity("new-subject", false, true));

    service.register("key-1", request());

    var captor = org.mockito.ArgumentCaptor.forClass(RegistrationRepository.RegistrationData.class);
    verify(registrations).complete(eq(OPERATION_ID), eq(LEARNER_ID), captor.capture());
    RegistrationRepository.RegistrationData stored = captor.getValue();
    assertThat(stored.termsVersion()).isEqualTo(TERMS);
    assertThat(stored.termsRef()).isEqualTo("terms/v1");
    assertThat(stored.privacyRef()).isEqualTo("privacy/v1");
    assertThat(stored.adultVersion()).isEqualTo(ADULT);
    // Normalization is what makes the email and mobile uniqueness constraints meaningful.
    assertThat(stored.email()).isEqualTo("asha.iyer@example.com");
    assertThat(stored.mobile()).isEqualTo("+919876543210");
  }

  // -------------------------------------------------------------------------------------------
  // Idempotency
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("replaying a completed operation repeats no side effect")
  void replayOfACompletedOperationIsANoOp() {
    when(registrations.start(anyString(), anyString()))
        .thenReturn(new RegistrationRepository.Operation(
            OPERATION_ID, "fingerprint", "EMAIL_PENDING", "existing-subject"));

    RegistrationService.RegistrationResponse response = service.register("key-1", request());

    verify(identities, never()).createLearner(anyString(), any());
    verify(identities, never()).sendVerificationEmail(anyString());
    verify(registrations, never()).complete(any(), any(), any());
    assertThat(response.operationId()).isEqualTo(OPERATION_ID);
    assertThat(counter("replayed")).isEqualTo(1d);
  }

  @Test
  @DisplayName("replaying a completed operation does not consume email quota")
  void replayDoesNotChargeEmailQuota() {
    when(registrations.start(anyString(), anyString()))
        .thenReturn(new RegistrationRepository.Operation(
            OPERATION_ID, "fingerprint", "EMAIL_PENDING", "existing-subject"));

    service.register("key-1", request());
    service.register("key-1", request());
    service.register("key-1", request());

    // The quota was charged before start() could recognise the replay, so a client retrying its own
    // success would eventually be answered 429 for a request that does nothing.
    verify(ceilings, never()).consume(
        org.mockito.ArgumentMatchers.startsWith("registration-email:"), anyInt(), anyInt());
  }

  @Test
  @DisplayName("a genuine new attempt still consumes email quota")
  void newAttemptStillChargesEmailQuota() {
    when(identities.createLearner(anyString(), any()))
        .thenReturn(new IdentityProviderPort.Identity("new-subject", false, true));

    service.register("key-1", request());

    verify(ceilings).consume(
        org.mockito.ArgumentMatchers.startsWith("registration-email:"), anyInt(), anyInt());
  }

  @Test
  @DisplayName("an idempotency conflict is refused before quota is charged")
  void idempotencyConflictIsRefusedBeforeCharging() {
    when(registrations.start(anyString(), anyString()))
        .thenThrow(RegistrationException.idempotencyKeyConflict());

    assertThatCode("REGISTRATION_IDEMPOTENCY_KEY_CONFLICT",
        () -> service.register("key-1", request()));

    // Claiming the operation first must not have weakened the different-body protection.
    verify(ceilings, never()).consume(anyString(), anyInt(), anyInt());
    verify(identities, never()).createLearner(anyString(), any());
  }

  @Test
  @DisplayName("the registration throttle advertises the window actually in force")
  void registrationThrottleAdvertisesItsWindow() {
    when(ceilings.consume(anyString(), anyInt(), anyInt())).thenReturn(false);

    assertThatThrownBy(() -> service.register("key-1", request()))
        .isInstanceOf(RegistrationException.class)
        .satisfies(failure -> {
          RegistrationException rejected = (RegistrationException) failure;
          assertThat(rejected.code()).isEqualTo("REGISTRATION_RATE_LIMITED");
          // The window is an hour; a hardcoded 300 told a well-behaved client to retry twelve times
          // too early.
          assertThat(rejected.retryAfterSeconds()).isEqualTo(3600L);
        });
  }

  @Test
  @DisplayName("a failed attempt is marked recoverable and counted")
  void failureMarksTheOperationRecoverable() {
    when(identities.createLearner(anyString(), any()))
        .thenThrow(RegistrationException.identityProviderUnavailable("createLearner",
            new IllegalStateException("boom")));

    assertThatThrownBy(() -> service.register("key-1", request()))
        .isInstanceOf(RegistrationException.class)
        .extracting(failure -> ((RegistrationException) failure).code())
        .isEqualTo("IDENTITY_PROVIDER_UNAVAILABLE");

    // Without this the row stays at STARTED forever, and FAILED_RECOVERABLE is a state declared in
    // the schema that no code can reach.
    verify(registrations).markFailedRecoverable(OPERATION_ID);
    verify(registrations).audit(eq(OPERATION_ID), any(), any(), eq("LEARNER_REGISTRATION_FAILED"),
        eq("FAILURE"), eq("IDENTITY_PROVIDER_UNAVAILABLE"));
    assertThat(counter("failure")).isEqualTo(1d);
  }

  @Test
  @DisplayName("the operation is not marked complete until the verification mail is accepted")
  void doesNotMarkCompleteWhenTheVerificationMailFails() {
    when(identities.createLearner(anyString(), any()))
        .thenReturn(new IdentityProviderPort.Identity("new-subject", false, true));
    org.mockito.Mockito.doThrow(RegistrationException.identityProviderUnavailable(
            "sendVerificationEmail", new IllegalStateException("smtp down")))
        .when(identities).sendVerificationEmail(anyString());

    assertThatThrownBy(() -> service.register("key-1", request()))
        .isInstanceOf(RegistrationException.class);

    // EMAIL_PENDING is what makes a replay a no-op. Reaching it before the mail was accepted would
    // strand the learner: their retry would short-circuit and never trigger a second send.
    verify(registrations, never()).markEmailRequested(OPERATION_ID);
    verify(registrations).markFailedRecoverable(OPERATION_ID);
  }

  // -------------------------------------------------------------------------------------------
  // Input and configuration refusals
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("registration is refused when the capability is disabled")
  void refusesWhenDisabled() {
    properties.setEnabled(false);
    assertThatCode("REGISTRATION_DISABLED", () -> service.register("key-1", request()));
    verify(identities, never()).createLearner(anyString(), any());
  }

  @Test
  @DisplayName("a missing, blank or oversized Idempotency-Key is refused")
  void requiresABoundedIdempotencyKey() {
    assertThatCode("REGISTRATION_IDEMPOTENCY_KEY_INVALID",
        () -> service.register(null, request()));
    assertThatCode("REGISTRATION_IDEMPOTENCY_KEY_INVALID", () -> service.register("  ", request()));
    assertThatCode("REGISTRATION_IDEMPOTENCY_KEY_INVALID",
        () -> service.register("k".repeat(129), request()));
  }

  @Test
  @DisplayName("a mismatched password confirmation is refused before any provider call")
  void refusesMismatchedPasswordConfirmation() {
    RegistrationRequest mismatched = new RegistrationRequest("Asha", "Iyer",
        "asha@example.com", "9876543210", "IN", "Pune", "correct horse battery",
        "correct horse batteries", TERMS, PRIVACY, ADULT, true, true, true);
    assertThatCode("PASSWORD_CONFIRMATION_MISMATCH", () -> service.register("key-1", mismatched));
    verify(identities, never()).createLearner(anyString(), any());
  }

  @Test
  @DisplayName("a consent or attestation version this deployment did not issue is refused")
  void refusesUnknownConsentVersions() {
    assertThatCode("CONSENT_VERSION_UNKNOWN", () -> service.register("key-1",
        new RegistrationRequest("Asha", "Iyer", "asha@example.com", "9876543210", "IN", "Pune",
            "correct horse battery", "correct horse battery", "terms-v99", PRIVACY, ADULT,
            true, true, true)));
    assertThatCode("CONSENT_VERSION_UNKNOWN", () -> service.register("key-2",
        new RegistrationRequest("Asha", "Iyer", "asha@example.com", "9876543210", "IN", "Pune",
            "correct horse battery", "correct horse battery", TERMS, "privacy-v99", ADULT,
            true, true, true)));
    assertThatCode("CONSENT_VERSION_UNKNOWN", () -> service.register("key-3",
        new RegistrationRequest("Asha", "Iyer", "asha@example.com", "9876543210", "IN", "Pune",
            "correct horse battery", "correct horse battery", TERMS, PRIVACY, "adult-v99",
            true, true, true)));
    verify(identities, never()).createLearner(anyString(), any());
  }

  @Test
  @DisplayName("an unaccepted statement is refused even when the versions are correct")
  void refusesUnacceptedStatements() {
    assertThatCode("CONSENT_VERSION_UNKNOWN", () -> service.register("key-1",
        new RegistrationRequest("Asha", "Iyer", "asha@example.com", "9876543210", "IN", "Pune",
            "correct horse battery", "correct horse battery", TERMS, PRIVACY, ADULT,
            true, true, false)));
  }

  @Test
  @DisplayName("an invalid mobile number is refused before any provider call")
  void refusesAnInvalidMobileNumber() {
    RegistrationRequest bad = new RegistrationRequest("Asha", "Iyer", "asha@example.com",
        "12345", "IN", "Pune", "correct horse battery", "correct horse battery",
        TERMS, PRIVACY, ADULT, true, true, true);
    assertThatCode("INVALID_MOBILE_NUMBER", () -> service.register("key-1", bad));
    verify(identities, never()).createLearner(anyString(), any());
  }

  @Test
  @DisplayName("the per-email ceiling refuses before the provider is called")
  void refusesWhenThePerEmailCeilingIsReached() {
    when(ceilings.consume(anyString(), anyInt(), anyInt())).thenReturn(false);
    assertThatCode("REGISTRATION_RATE_LIMITED", () -> service.register("key-1", request()));
    // The ceiling exists to bound cost. Calling the provider first would spend exactly what it is
    // meant to protect: a Keycloak write and an outbound verification mail.
    verify(identities, never()).createLearner(anyString(), any());
  }

  private static void assertThatCode(String expectedCode, org.junit.jupiter.api.function.Executable call) {
    assertThatThrownBy(call::execute)
        .isInstanceOf(RegistrationException.class)
        .extracting(failure -> ((RegistrationException) failure).code())
        .isEqualTo(expectedCode);
  }
}
