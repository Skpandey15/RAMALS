package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Source- and schema-level invariants for the professional registration boundary.
 *
 * <p>These assert properties that behaviour tests cannot reach: that a field does not exist, that a
 * migration does not declare a column, that a realm does not grant a role. A behavioural test can
 * only demonstrate that today's code does not do something; these demonstrate that tomorrow's cannot
 * without failing the build.
 */
class RegistrationSecurityContractTests {

  private static final Path REPOSITORY_ROOT = Path.of("..");

  private static String read(String relativePath) throws Exception {
    return Files.readString(REPOSITORY_ROOT.resolve(relativePath));
  }

  /**
   * Returns Java source with comments removed.
   *
   * <p>The earlier version of the SMS-is-not-MFA assertion scanned raw source for {@code amr},
   * {@code acr} and {@code MfaAuthorization}. That could not distinguish code that sets a claim from
   * a comment explaining that the claim must never be set — so documenting the invariant broke the
   * test that protects it, which is a strong incentive to leave security decisions undocumented.
   * Stripping comments first makes the assertion mean what it always intended: no <em>executable</em>
   * reference to authentication-assurance machinery.
   */
  private static String codeWithoutComments(String source) {
    return source
        .replaceAll("(?s)/\\*.*?\\*/", " ")
        .replaceAll("(?m)//.*$", " ");
  }

  @Test
  @DisplayName("the public contract has no field through which a role could be requested")
  void publicContractCannotRequestAnyRole() {
    assertThat(Arrays.stream(RegistrationRequest.class.getRecordComponents())
        .map(component -> component.getName()))
        .doesNotContain("role", "roles", "realmRole", "realmRoles", "clientRole", "clientRoles",
            "authorities", "groups");
  }

  @Test
  @DisplayName("LEARNER is the only realm role the adapter can name")
  void identityAdapterAssignsOnlyTheLearnerRole() throws Exception {
    String adapter = codeWithoutComments(read(
        "learning-platform/src/main/java/io/ramals/learningplatform/registration/"
            + "KeycloakRegistrationAdminClient.java"));
    assertThat(adapter).doesNotContain("INSTRUCTOR", "CONTENT_AUTHOR", "\"ADMIN\"", "\"SERVICE\"");
    // The role is a constant, so no call path can be parameterised into granting a different one.
    assertThat(adapter).contains("String LEARNER_ROLE = \"LEARNER\"");
  }

  @Test
  @DisplayName("core.learner stays PII-free and the challenge table stores only a keyed HMAC")
  void migrationKeepsCoreLearnerPiiFreeAndPersistsOnlyKeyedHmac() throws Exception {
    String learnerMigration =
        read("learning-platform/src/main/resources/db/migration/V004__learner_domain.sql");
    String coreLearnerTable = learnerMigration.substring(
        learnerMigration.indexOf("CREATE TABLE core.learner"),
        learnerMigration.indexOf("CREATE TABLE core.learner_goal"));
    assertThat(coreLearnerTable).doesNotContain(
        "first_name", "last_name", "email", "mobile", "city", "country", "date_of_birth");

    String migration = read("learning-platform/src/main/resources/db/migration/"
        + "V041__professional_registration_verification.sql");
    assertThat(migration).contains(
        "otp_hmac BYTEA NOT NULL",
        "hmac_key_version",
        "attempt_count",
        "max_attempts",
        "policy_version",
        "superseded_at",
        "resend_generation",
        "CREATE UNIQUE INDEX uq_learner_contact_verified_mobile");
    assertThat(migration).doesNotContain("plaintext_otp", "otp_plaintext", "otp_value");
  }

  @Test
  @DisplayName("no registration table stores a password or any credential material")
  void registrationPersistenceHasNoCredentialColumn() throws Exception {
    String migration = read("learning-platform/src/main/resources/db/migration/"
        + "V041__professional_registration_verification.sql");
    assertThat(migration.toLowerCase()).doesNotContain(
        "password", "credential", "secret", "confirm_password");
  }

  @Test
  @DisplayName("the verified-mobile reservation is a database constraint, not an application check")
  void mobileUniquenessIsEnforcedByAPartialUniqueIndex() throws Exception {
    String migration = read("learning-platform/src/main/resources/db/migration/"
        + "V041__professional_registration_verification.sql");
    assertThat(migration).contains(
        "CREATE UNIQUE INDEX uq_learner_contact_verified_mobile",
        "ON identity.learner_contact (mobile_e164) WHERE mobile_verified_at IS NOT NULL");
  }

  @Test
  @DisplayName("the registration audit trail is append-only at the database level")
  void registrationAuditIsAppendOnly() throws Exception {
    String migration = read("learning-platform/src/main/resources/db/migration/"
        + "V041__professional_registration_verification.sql");
    assertThat(migration).contains(
        "BEFORE UPDATE OR DELETE ON audit.registration_event",
        "append-only");
  }

  @Test
  @DisplayName("the realm uses a dedicated admin client, without manage-realm or self-registration")
  void realmUsesDedicatedLeastPrivilegeClient() throws Exception {
    String realm = read("infrastructure/docker/keycloak/ramals-realm.json");
    assertThat(realm).contains(
        "\"registrationAllowed\": false",
        "\"clientId\": \"ramals-registration-admin\"",
        "\"manage-users\"",
        "\"view-users\"");
    assertThat(realm).doesNotContain("\"manage-realm\"", "\"realm-admin\"");
    // The AI plane's workload identity must not acquire user-management rights (M1-ADR-014).
    int workloadClient = realm.indexOf("ramals-core-workload");
    assertThat(workloadClient).isGreaterThan(-1);
    assertThat(realm.substring(workloadClient, Math.min(realm.length(), workloadClient + 1200)))
        .doesNotContain("manage-users");
  }

  @Test
  @DisplayName("Keycloak, not RAMALS, owns verification mail, and a local sink is wired for DEV")
  void realmOwnsEmailVerification() throws Exception {
    String realm = read("infrastructure/docker/keycloak/ramals-realm.json");
    assertThat(realm).contains("\"verifyEmail\": true", "\"smtpServer\"");
  }

  @Test
  @DisplayName("SMS ownership verification cannot mint authentication assurance")
  void smsOwnershipCodeCannotMintAuthenticationAssurance() throws Exception {
    String service = codeWithoutComments(read(
        "learning-platform/src/main/java/io/ramals/learningplatform/registration/"
            + "MobileVerificationService.java"));
    assertThat(service).doesNotContain(
        "amr", "acr", "MfaAuthorization", "CONFIGURE_TOTP", "otpPolicy", "GrantedAuthority");

    String controller = codeWithoutComments(read(
        "learning-platform/src/main/java/io/ramals/learningplatform/registration/"
            + "OnboardingController.java"));
    assertThat(controller).doesNotContain("MfaAuthorization", "CONFIGURE_TOTP");
  }

  /**
   * Returns the argument text of each call to {@code prefix}, so an assertion can be scoped to what
   * is actually passed to a logger or a meter.
   *
   * <p>Scanning whole files instead would be unusable here: {@code KeycloakRegistrationAdminClient}
   * legitimately builds a provider request body containing {@code "email"}, and a file-wide scan for
   * that literal cannot tell a JSON field being sent to Keycloak from a log field being written to
   * disk. Narrowing to the call site is what makes the assertion about disclosure rather than about
   * the presence of a word.
   */
  private static java.util.List<String> callArguments(String code, String prefix) {
    java.util.List<String> calls = new java.util.ArrayList<>();
    int cursor = code.indexOf(prefix);
    while (cursor >= 0) {
      int end = code.indexOf(");", cursor);
      calls.add(end < 0 ? code.substring(cursor) : code.substring(cursor, end));
      cursor = code.indexOf(prefix, cursor + prefix.length());
    }
    return calls;
  }

  private static java.util.List<Path> registrationSources() throws Exception {
    try (var sources = Files.list(REPOSITORY_ROOT.resolve(
        "learning-platform/src/main/java/io/ramals/learningplatform/registration"))) {
      return sources.filter(path -> path.toString().endsWith(".java")).toList();
    }
  }

  @Test
  @DisplayName("no log event carries contact PII, a credential or a verification code")
  void registrationCodeNeverLogsSensitiveValues() throws Exception {
    for (Path source : registrationSources()) {
      String code = codeWithoutComments(Files.readString(source));
      for (String call : callArguments(code, "BusinessEventLogger.")) {
        assertThat(call)
            .as("%s logs a field it must not", source.getFileName())
            .doesNotContain("\"otp\"", "\"password\"", "\"email\"", "\"mobile\"",
                "\"mobileE164\"", "\"clientSecret\"", "\"secret\"", "\"subject\"",
                "\"sourceAddress\"", "\"ipAddress\"");
      }
    }
  }

  @Test
  @DisplayName("metric labels stay low cardinality and carry no identifier")
  void metricsUseBoundedLabelsOnly() throws Exception {
    for (Path source : registrationSources()) {
      String code = codeWithoutComments(Files.readString(source));
      for (String call : callArguments(code, "meterRegistry.counter(")) {
        // §22: email, mobile, address, OIDC subject and the code are unbounded, so as label values
        // they would multiply the series per learner and put contact data in the metrics store,
        // which is neither access-controlled as PII nor covered by the retention rules that apply
        // to identity.learner_contact.
        assertThat(call)
            .as("%s tags a metric with an unbounded value", source.getFileName())
            .doesNotContain("\"subject\"", "\"email\"", "\"mobile\"", "\"learnerId\"",
                "\"otp\"", "\"challengeId\"", "\"operationId\"", "\"sourceAddress\"");
      }
    }
  }
}
