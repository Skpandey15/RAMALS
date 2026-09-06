package io.ramals.learningplatform.mcp.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * M2-ADR-031: mints a delegated learner-context token. Java is the sole issuer -- deliberately not a
 * second identity issuer in M1-ADR-003's own rejected sense, since this asserts no service identity
 * at all, only a scoped capability grant riding alongside the unchanged {@code ramals-ai} workload
 * authentication leg (M2-ADR-031 §B).
 *
 * <p><b>This method performs no authorization of its own.</b> Every argument MUST already be a
 * server-side-decided value: an interaction Java itself is currently authorizing, the learner it
 * concerns, the domain it is scoped to, and the exact capabilities that interaction may invoke. There
 * is deliberately no overload accepting a caller-supplied request DTO with free {@code learnerId}/
 * {@code domain}/{@code capabilities} fields -- the only way to reach this class is from Java code
 * that has already made that decision. MCP-1 wires no such call site yet (there is no capability to
 * authorize access to); this class is package-visible-equivalent in intent and is exercised directly
 * by its own tests until a real integration point exists (M2-ADR-031's own "keep the issuer internal
 * ... rather than wiring issuance into unrelated flows prematurely").
 */
public class DelegatedLearnerContextIssuer {

  private final String issuer;
  private final String audience;
  private final Duration ttl;
  private final String keyId;
  private final MACSigner signer;
  private final Clock clock;

  /**
   * @param signingKey the current HMAC signing key. Must be at least 256 bits (32 bytes), per
   *     RFC 7518 §3.2 -- {@link MACSigner} itself enforces this and rejects a shorter key.
   */
  public DelegatedLearnerContextIssuer(
      String issuer, String audience, Duration ttl, String keyId, byte[] signingKey, Clock clock) {
    this.issuer = issuer;
    this.audience = audience;
    this.ttl = ttl;
    this.keyId = keyId;
    this.clock = clock;
    try {
      this.signer = new MACSigner(signingKey);
    } catch (JOSEException invalidKey) {
      throw new IllegalArgumentException(
          "delegated-context signing key is invalid (must be at least 256 bits)", invalidKey);
    }
  }

  /**
   * Mints a token authorizing exactly this already-decided interaction, learner, domain and
   * capability set. Reuses {@link DelegatedLearnerContext}'s own compact-constructor validation
   * (non-blank fields, non-empty capability set, each capability matching the governed name shape)
   * rather than duplicating it -- a caller defect here fails exactly the same way it would on the
   * receiving side.
   */
  public String issue(
      String interactionId, String learnerScope, String domainScope, Set<String> capabilities) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(ttl);
    // Validates every argument via the record's own compact constructor before anything is signed.
    DelegatedLearnerContext context =
        new DelegatedLearnerContext(interactionId, learnerScope, domainScope, capabilities, now, expiresAt);

    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(issuer)
        .audience(audience)
        .issueTime(Date.from(context.issuedAt()))
        .notBeforeTime(Date.from(context.issuedAt()))
        .expirationTime(Date.from(context.expiresAt()))
        .claim("interactionId", context.interactionId())
        .claim("learnerScope", context.learnerScope())
        .claim("domainScope", context.domainScope())
        .claim("capabilities", List.copyOf(context.capabilities()))
        .build();

    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(keyId).build(), claims);
    try {
      jwt.sign(signer);
    } catch (JOSEException failure) {
      throw new IllegalStateException("failed to sign delegated learner-context token", failure);
    }
    return jwt.serialize();
  }
}
