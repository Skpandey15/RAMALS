package io.ramals.learningplatform.registration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for professional registration and verification.
 *
 * <p><strong>Everything sensitive is externalized and nothing is defaulted.</strong> The Keycloak
 * admin secret and the OTP key ring have no in-code default, so a deployment that fails to supply
 * them supplies blank — which {@link RegistrationConfiguration} turns into a refusal to start rather
 * than into a service that runs with no key. A convenient default here would be a credential in the
 * repository, and a credential in the repository is a credential.
 *
 * <p><strong>{@code enabled} defaults to false.</strong> Registration is opt-in per deployment. A new
 * environment that has not yet been given a Keycloak admin client and key material does not
 * half-enable the feature; it simply does not offer it.
 */
@ConfigurationProperties("ramals.registration")
public class RegistrationProperties {

  private boolean enabled;

  /**
   * The deployment's environment marker.
   *
   * <p>Read only to answer "is this production", and only ever to make behaviour stricter: it selects
   * the refusal of the fake SMS provider. Nothing is unlocked by claiming to be production, so
   * mis-setting it fails closed.
   */
  private String environment = "dev";

  private final Keycloak keycloak = new Keycloak();
  private final Consent consent = new Consent();
  private final Otp otp = new Otp();
  private final Sms sms = new Sms();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String environment) {
    this.environment = environment;
  }

  public Keycloak getKeycloak() {
    return keycloak;
  }

  public Consent getConsent() {
    return consent;
  }

  public Otp getOtp() {
    return otp;
  }

  public Sms getSms() {
    return sms;
  }

  public boolean production() {
    return "prod".equalsIgnoreCase(environment) || "production".equalsIgnoreCase(environment);
  }

  /** The dedicated registration-admin client (M1-ADR-014), never {@code ramals-core-workload}. */
  public static class Keycloak {

    private String baseUrl;
    private String realm;
    private String clientId;
    private String clientSecret;

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getRealm() {
      return realm;
    }

    public void setRealm(String realm) {
      this.realm = realm;
    }

    public String getClientId() {
      return clientId;
    }

    public void setClientId(String clientId) {
      this.clientId = clientId;
    }

    public String getClientSecret() {
      return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
      this.clientSecret = clientSecret;
    }

    /** Never renders the secret: this object reaches actuator and diagnostic output. */
    @Override
    public String toString() {
      return "Keycloak[baseUrl=" + baseUrl + ", realm=" + realm + ", clientId=" + clientId
          + ", clientSecret=REDACTED]";
    }
  }

  /**
   * Server-known consent and attestation versions.
   *
   * <p>These are the values written to {@code identity.learner_contact}. A submitted version is
   * compared against them and then discarded, so acceptance is always recorded against a revision
   * this deployment actually issued.
   */
  public static class Consent {

    private String termsVersion;
    private String termsRef;
    private String privacyVersion;
    private String privacyRef;
    private String adultStatementVersion;

    public String getTermsVersion() {
      return termsVersion;
    }

    public void setTermsVersion(String termsVersion) {
      this.termsVersion = termsVersion;
    }

    public String getTermsRef() {
      return termsRef;
    }

    public void setTermsRef(String termsRef) {
      this.termsRef = termsRef;
    }

    public String getPrivacyVersion() {
      return privacyVersion;
    }

    public void setPrivacyVersion(String privacyVersion) {
      this.privacyVersion = privacyVersion;
    }

    public String getPrivacyRef() {
      return privacyRef;
    }

    public void setPrivacyRef(String privacyRef) {
      this.privacyRef = privacyRef;
    }

    public String getAdultStatementVersion() {
      return adultStatementVersion;
    }

    public void setAdultStatementVersion(String adultStatementVersion) {
      this.adultStatementVersion = adultStatementVersion;
    }
  }

  /** OTP policy. {@code policyVersion} is stamped on each challenge so a change is reconstructable. */
  public static class Otp {

    private String hmacKeyVersion = "v1";
    private String hmacKeyRing = "";
    private String policyVersion = "otp-v1";
    private int ttlSeconds = 300;
    private int resendCooldownSeconds = 45;
    private int maxAttempts = 5;

    public String getHmacKeyVersion() {
      return hmacKeyVersion;
    }

    public void setHmacKeyVersion(String hmacKeyVersion) {
      this.hmacKeyVersion = hmacKeyVersion;
    }

    public String getHmacKeyRing() {
      return hmacKeyRing;
    }

    public void setHmacKeyRing(String hmacKeyRing) {
      this.hmacKeyRing = hmacKeyRing;
    }

    public String getPolicyVersion() {
      return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
      this.policyVersion = policyVersion;
    }

    public int getTtlSeconds() {
      return ttlSeconds;
    }

    public void setTtlSeconds(int ttlSeconds) {
      this.ttlSeconds = ttlSeconds;
    }

    public int getResendCooldownSeconds() {
      return resendCooldownSeconds;
    }

    public void setResendCooldownSeconds(int resendCooldownSeconds) {
      this.resendCooldownSeconds = resendCooldownSeconds;
    }

    public int getMaxAttempts() {
      return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
    }

    /** Never renders the key ring. */
    @Override
    public String toString() {
      return "Otp[hmacKeyVersion=" + hmacKeyVersion + ", hmacKeyRing=REDACTED, policyVersion="
          + policyVersion + ", ttlSeconds=" + ttlSeconds + ", resendCooldownSeconds="
          + resendCooldownSeconds + ", maxAttempts=" + maxAttempts + "]";
    }
  }

  public static class Sms {

    private String provider = "fake";

    /**
     * The deployment-wide hourly send ceiling.
     *
     * <p>The control that bounds the bill. Per-subject and per-number ceilings constrain one abuser;
     * only this one constrains an attacker who can mint subjects faster than the per-subject counter
     * can matter.
     */
    private int globalHourlyBudget = 100;

    /** Days of abuse-counter history to retain before {@code RegistrationAbuseCounterPurgeWorker} deletes it. */
    private int abuseCounterRetentionDays = 7;

    public String getProvider() {
      return provider;
    }

    public void setProvider(String provider) {
      this.provider = provider;
    }

    public int getGlobalHourlyBudget() {
      return globalHourlyBudget;
    }

    public void setGlobalHourlyBudget(int globalHourlyBudget) {
      this.globalHourlyBudget = globalHourlyBudget;
    }

    public int getAbuseCounterRetentionDays() {
      return abuseCounterRetentionDays;
    }

    public void setAbuseCounterRetentionDays(int abuseCounterRetentionDays) {
      this.abuseCounterRetentionDays = abuseCounterRetentionDays;
    }
  }

  private final Resend resend = new Resend();

  public Resend getResend() {
    return resend;
  }

  /**
   * Out-of-band delivery of verification-resend mail.
   *
   * <p>The send is deliberately not on the request path: inline, only a request for a genuinely
   * unverified address paid for the provider call, which made response time an account-enumeration
   * oracle that the uniform response body had just closed.
   */
  public static class Resend {

    private boolean enabled = true;
    /** Short: this is a learner waiting on an email, not a background reconciliation. */
    private long intervalMs = 5_000;
    private int batchSize = 20;
    private int maxAttempts = 5;
    private long initialBackoffMillis = 1_000;
    private long maxBackoffMillis = 60_000;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public long getIntervalMs() {
      return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
      this.intervalMs = intervalMs;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
      return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
    }

    public long getInitialBackoffMillis() {
      return initialBackoffMillis;
    }

    public void setInitialBackoffMillis(long initialBackoffMillis) {
      this.initialBackoffMillis = initialBackoffMillis;
    }

    public long getMaxBackoffMillis() {
      return maxBackoffMillis;
    }

    public void setMaxBackoffMillis(long maxBackoffMillis) {
      this.maxBackoffMillis = maxBackoffMillis;
    }
  }
}
