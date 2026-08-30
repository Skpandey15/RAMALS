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
 * <p>Keyed, not hashed: a six-digit code has a million values, so an unkeyed digest of one is
 * exhaustible in microseconds by anyone who obtains the stored value. An HMAC under a key held
 * outside the database means a stolen table yields nothing. Reversible encryption would be worse
 * again, since verification never needs to recover the code.
 *
 * <p>The canonical encoding is
 * {@code HMAC-SHA-256(Kv, UTF-8(challengeId) || 0x00 || UTF-8(mobileE164) || 0x00 || ASCII(otp))},
 * with the challenge id in lowercase UUID text form, the number in E.164 including the leading
 * plus, and six zero-padded ASCII digits. The NUL separators make the encoding unambiguous - a MAC
 * over an ambiguous encoding authenticates the ambiguity - and cannot occur inside any field.
 *
 * <p>Binding to the challenge and number is the point: a MAC over the code alone would verify
 * against any challenge. Keys are a versioned ring and each challenge records the version it was
 * created under, so rotation does not invalidate codes already in flight.
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
   * Parses {@code version:base64Key} entries. Validated at startup rather than at first use: the
   * moment to discover missing or malformed key material is while the container is starting.
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
   * Compares two MACs in constant time. {@code Arrays.equals} is not, and a timing-visible
   * comparison against a stored MAC is a distinguisher an attacker can drive.
   */
  boolean matches(byte[] expected, byte[] actual) {
    return MessageDigest.isEqual(expected, actual);
  }
}
