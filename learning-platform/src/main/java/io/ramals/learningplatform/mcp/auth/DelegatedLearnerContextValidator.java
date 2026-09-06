package io.ramals.learningplatform.mcp.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M2-ADR-031: independently verifies a delegated learner-context token and returns a typed,
 * immutable {@link DelegatedLearnerContext} -- never a generic {@code Map<String, Object>} to
 * application code, and never partially trusts a token that fails any single check (fail closed).
 *
 * <p>Validates, in order: signature (against the key named by the token's own {@code kid}, which
 * must be either the current or, during a rotation window, the previous configured key -- never any
 * other value), issuer, audience (exactly {@code ramals-mcp} by default, never {@code ramals-api} or
 * {@code ramals-ai}), expiry (with a small clock-skew allowance), not-before, and finally the
 * required structural claims: {@code interactionId}, {@code learnerScope}, {@code domainScope}, and a
 * non-empty {@code capabilities} array whose every entry matches
 * {@link DelegatedLearnerContext#CAPABILITY_NAME_PATTERN} -- so a wildcard or malformed capability
 * name is rejected here, before a {@link DelegatedLearnerContext} is ever constructed.
 *
 * <p>{@link DelegatedLearnerContextException} never carries the raw token, its signature, or any
 * claim value -- only a stable {@link DelegatedLearnerContextException.Reason} -- so it is always
 * safe to log directly.
 */
public class DelegatedLearnerContextValidator {

  private static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

  private final String expectedIssuer;
  private final String expectedAudience;
  private final Map<String, byte[]> verificationKeysByKeyId;
  private final Clock clock;

  /**
   * @param verificationKeysByKeyId every key this validator may accept, keyed by the {@code kid} a
   *     token's header must carry -- normally the current signing key id, plus, during a rotation
   *     window, the previous one. A token whose {@code kid} names any other value, or carries none,
   *     is rejected ({@code UNKNOWN_KEY_ID}).
   */
  public DelegatedLearnerContextValidator(
      String expectedIssuer, String expectedAudience, Map<String, byte[]> verificationKeysByKeyId,
      Clock clock) {
    this.expectedIssuer = expectedIssuer;
    this.expectedAudience = expectedAudience;
    this.verificationKeysByKeyId = Map.copyOf(verificationKeysByKeyId);
    this.clock = clock;
  }

  public DelegatedLearnerContext validate(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      throw fail(DelegatedLearnerContextException.Reason.MISSING);
    }

    SignedJWT jwt;
    try {
      jwt = SignedJWT.parse(rawToken);
    } catch (ParseException malformed) {
      throw fail(DelegatedLearnerContextException.Reason.MALFORMED);
    }

    String keyId = jwt.getHeader().getKeyID();
    byte[] verificationKey = keyId == null ? null : verificationKeysByKeyId.get(keyId);
    if (verificationKey == null) {
      throw fail(DelegatedLearnerContextException.Reason.UNKNOWN_KEY_ID);
    }

    boolean verified;
    try {
      verified = jwt.verify(new MACVerifier(verificationKey));
    } catch (JOSEException signatureFailure) {
      throw fail(DelegatedLearnerContextException.Reason.BAD_SIGNATURE);
    }
    if (!verified) {
      throw fail(DelegatedLearnerContextException.Reason.BAD_SIGNATURE);
    }

    JWTClaimsSet claims;
    try {
      claims = jwt.getJWTClaimsSet();
    } catch (ParseException malformed) {
      throw fail(DelegatedLearnerContextException.Reason.MALFORMED);
    }

    if (!expectedIssuer.equals(claims.getIssuer())) {
      throw fail(DelegatedLearnerContextException.Reason.WRONG_ISSUER);
    }

    List<String> audiences = claims.getAudience();
    if (audiences == null || !audiences.contains(expectedAudience)) {
      throw fail(DelegatedLearnerContextException.Reason.WRONG_AUDIENCE);
    }

    Instant now = clock.instant();
    Date expiration = claims.getExpirationTime();
    if (expiration == null || !now.isBefore(expiration.toInstant().plus(CLOCK_SKEW))) {
      throw fail(DelegatedLearnerContextException.Reason.EXPIRED);
    }
    Date notBefore = claims.getNotBeforeTime();
    if (notBefore != null && now.isBefore(notBefore.toInstant().minus(CLOCK_SKEW))) {
      throw fail(DelegatedLearnerContextException.Reason.NOT_YET_VALID);
    }

    String interactionId = stringClaim(claims, "interactionId");
    if (isBlank(interactionId)) {
      throw fail(DelegatedLearnerContextException.Reason.MISSING_INTERACTION_ID);
    }
    String learnerScope = stringClaim(claims, "learnerScope");
    if (isBlank(learnerScope)) {
      throw fail(DelegatedLearnerContextException.Reason.MISSING_LEARNER_SCOPE);
    }
    String domainScope = stringClaim(claims, "domainScope");
    if (isBlank(domainScope)) {
      throw fail(DelegatedLearnerContextException.Reason.MISSING_DOMAIN_SCOPE);
    }

    List<String> rawCapabilities;
    try {
      rawCapabilities = claims.getStringListClaim("capabilities");
    } catch (ParseException notAList) {
      throw fail(DelegatedLearnerContextException.Reason.MISSING_CAPABILITIES);
    }
    if (rawCapabilities == null || rawCapabilities.isEmpty()) {
      throw fail(DelegatedLearnerContextException.Reason.MISSING_CAPABILITIES);
    }
    Set<String> capabilities = new LinkedHashSet<>(rawCapabilities);
    for (String capability : capabilities) {
      if (capability == null
          || !DelegatedLearnerContext.CAPABILITY_NAME_PATTERN.matcher(capability).matches()) {
        throw fail(DelegatedLearnerContextException.Reason.INVALID_CAPABILITY_FORMAT);
      }
    }

    Instant issuedAt = claims.getIssueTime() != null ? claims.getIssueTime().toInstant() : now;
    try {
      return new DelegatedLearnerContext(
          interactionId, learnerScope, domainScope, capabilities, issuedAt, expiration.toInstant());
    } catch (IllegalArgumentException structurallyInvalid) {
      throw fail(DelegatedLearnerContextException.Reason.MALFORMED);
    }
  }

  private static String stringClaim(JWTClaimsSet claims, String name) {
    try {
      return claims.getStringClaim(name);
    } catch (ParseException notAString) {
      return null;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static DelegatedLearnerContextException fail(DelegatedLearnerContextException.Reason reason) {
    return new DelegatedLearnerContextException(reason);
  }
}
