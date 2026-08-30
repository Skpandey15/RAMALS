package io.ramals.learningplatform.registration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The keyed verifier for mobile one-time codes.
 *
 * <p><strong>Why keyed, and not a hash.</strong> A six-digit code has a million possible values. An
 * unkeyed digest of one — even a strong digest, even salted with public data — is exhaustible in
 * microseconds by anyone who obtains the stored value, so a database disclosure would hand the
 * attacker every outstanding code. An HMAC under a key held outside the database means a stolen table
 * yields nothing without also stealing the key. Reversible encryption would be worse than either:
 * verification does not need to recover the code, so holding a mechanism that can is pure downside.
 *
 * <p><strong>The canonical encoding.</strong> The MAC is computed over exactly:
 *
 * <pre>
 *   HMAC-SHA-256(Kv, UTF-8(challengeId) || 0x00 || UTF-8(mobileE164) || 0x00 || ASCII(otp))
 * </pre>
 *
 * <p>{@code challengeId} is the lowercase canonical UUID text form. {@code mobileE164} is the
 * normalized number including its leading {@code +}. {@code otp} is exactly six ASCII digits,
 * zero-padded. The {@code 0x00} separators are what make the encoding unambiguous: without them the
 * concatenation of a challenge id and a number could equal a different pairing of the two, and a MAC
 * over an ambiguous encoding authenticates the ambiguity rather than the values. A NUL byte cannot
 * occur inside any of the three fields, so it cannot be used to forge a boundary.
 *
 * <p><strong>Binding to the challenge and the number is the point.</strong> A MAC over the code alone
 * would verify equally against any challenge, so a code observed for one learner's challenge would
 * satisfy another's. Including both identifiers makes each stored value useful for exactly one
 * verification.
 *
 * <p><strong>Rotation.</strong> Keys are supplied as a versioned ring, and each challenge records the
 * version it was created under, so a rotation does not invalidate codes already in flight: a
 * challenge is always verified with the key that produced it. Retiring a key is then just removing it
 * from the ring once no unexpired challenge names it.
 */
@Component
class OtpHmac {

  private static final String ALGORITHM = "HmacSHA256";

  /** 256 bits, matching the MAC's output. A shorter key is rejected rather than silently accepted. */
  private static final int MINIMUM_KEY_BYTES = 32;

  private final Map<String, byte[]> keyRing;
  private final String currentVersion;

  OtpHmac(RegistrationProperties properties) {
    this.currentVersion = properties.getOtp().getHmacKeyVersion();
    this.keyRing = parseKeyRing(properties.getOtp().getHmacKeyRing());
  }

  /**
   * Parses {@code version:base64Key} entries.
   *
   * <p>Validation happens here, at startup, rather than at first use. A deployment whose key material
   * is missing, malformed or too short is misconfigured, and the moment to discover that is while the
   * container is starting — not when the first learner cannot verify their number.
   */
  private static Map<String, byte[]> parseKeyRing(String configured) {
    Map<String, byte[]> ring = new LinkedHashMap<>();
    if (configured == null || configured.isBlank()) {
      return ring;
    }
    for (String entry : configured.split(",")) {
      String trimmed = entry.trim();
      int separator = trimmed.indexOf(':');
      if (separator <= 0) {
        throw new IllegalStateException(
            "OTP HMAC key ring entries must be formatted as version:base64Key.");
      }
      String version = trimmed.substring(0, separator);
      byte[] key;
      try {
        key = Base64.getDecoder().decode(trimmed.substring(separator + 1));
      } catch (IllegalArgumentException notBase64) {
        // The version is safe to name; the material is not, and never appears in the message.
        throw new IllegalStateException(
            "OTP HMAC key for version " + version + " is not valid Base64.", notBase64);
      }
      if (key.length < MINIMUM_KEY_BYTES) {
        throw new IllegalStateException("OTP HMAC key for version " + version + " is shorter than "
            + MINIMUM_KEY_BYTES + " bytes.");
      }
      ring.put(version, key);
    }
    return ring;
  }

  String currentVersion() {
    return currentVersion;
  }

  boolean hasUsableCurrentKey() {
    return keyRing.containsKey(currentVersion);
  }

  byte[] calculate(String keyVersion, UUID challengeId, String mobileE164, String otp) {
    byte[] key = keyRing.get(keyVersion);
    if (key == null) {
      throw new IllegalStateException("No OTP HMAC key is configured for version " + keyVersion + ".");
    }
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      mac.update(challengeId.toString().getBytes(StandardCharsets.UTF_8));
      mac.update((byte) 0);
      mac.update(mobileE164.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) 0);
      return mac.doFinal(otp.getBytes(StandardCharsets.US_ASCII));
    } catch (java.security.GeneralSecurityException unavailable) {
      // Deliberately not wrapped in a domain rejection: this is the verifier being broken, not the
      // learner's code being wrong, and it must not be reported to the caller as a bad code.
      throw new IllegalStateException("OTP verifier is unavailable.", unavailable);
    }
  }

  /**
   * Compares two MACs without leaking where they first differ.
   *
   * <p>{@link MessageDigest#isEqual} is the constant-time comparison; {@code Arrays.equals} and
   * {@code byte[].equals} are not, and a timing-visible comparison against a stored MAC is a
   * distinguisher an attacker can drive with repeated submissions.
   */
  boolean matches(byte[] expected, byte[] actual) {
    return MessageDigest.isEqual(expected, actual);
  }
}
