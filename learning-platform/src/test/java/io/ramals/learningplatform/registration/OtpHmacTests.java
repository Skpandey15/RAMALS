package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The keyed OTP verifier: context binding, key rotation, and startup validation of key material. */
class OtpHmacTests {

  private static final UUID CHALLENGE =
      UUID.fromString("01900000-0000-7000-8000-000000000041");
  private static final UUID OTHER_CHALLENGE =
      UUID.fromString("01900000-0000-7000-8000-000000000042");
  private static final String MOBILE = "+919876543210";

  private static String key(byte fill) {
    byte[] material = new byte[32];
    Arrays.fill(material, fill);
    return Base64.getEncoder().encodeToString(material);
  }

  private static OtpHmac hmac(String version, String ring) {
    RegistrationProperties properties = new RegistrationProperties();
    properties.getOtp().setHmacKeyVersion(version);
    properties.getOtp().setHmacKeyRing(ring);
    return new OtpHmac(properties);
  }

  @Test
  @DisplayName("the MAC is bound to the challenge, the number and the code")
  void macIsBoundToItsFullContext() {
    OtpHmac verifier = hmac("v1", "v1:" + key((byte) 7));
    byte[] expected = verifier.calculate("v1", CHALLENGE, MOBILE, "123456");

    assertThat(verifier.matches(expected, verifier.calculate("v1", CHALLENGE, MOBILE, "123456")))
        .isTrue();
    // A different code, number or challenge must not verify. Without the challenge in the input, a
    // code observed for one challenge would satisfy any other.
    assertThat(verifier.matches(expected, verifier.calculate("v1", CHALLENGE, MOBILE, "123457")))
        .isFalse();
    assertThat(verifier.matches(expected, verifier.calculate("v1", CHALLENGE, "+919876543211",
        "123456"))).isFalse();
    assertThat(verifier.matches(expected, verifier.calculate("v1", OTHER_CHALLENGE, MOBILE,
        "123456"))).isFalse();
  }

  @Test
  @DisplayName("the NUL separators make the encoding unambiguous")
  void separatorsPreventFieldBoundaryConfusion() {
    OtpHmac verifier = hmac("v1", "v1:" + key((byte) 7));
    // Without unambiguous separators, a MAC authenticates a concatenation that more than one field
    // split could produce. A NUL cannot appear inside a UUID, an E.164 number or a digit string, so
    // moving a boundary must change the MAC.
    assertThat(verifier.calculate("v1", CHALLENGE, "+91987654321", "0123456"))
        .isNotEqualTo(verifier.calculate("v1", CHALLENGE, "+919876543210", "123456"));
  }

  @Test
  @DisplayName("a challenge verifies under the key version it was created with")
  void rotationKeepsInFlightChallengesVerifiable() {
    // Two versions in the ring, current is v2. A challenge stamped v1 must still verify: rotation
    // must not invalidate codes already in a learner's hands.
    OtpHmac verifier = hmac("v2", "v1:" + key((byte) 7) + ",v2:" + key((byte) 9));
    byte[] issuedUnderV1 = verifier.calculate("v1", CHALLENGE, MOBILE, "123456");

    assertThat(verifier.currentVersion()).isEqualTo("v2");
    assertThat(verifier.matches(issuedUnderV1, verifier.calculate("v1", CHALLENGE, MOBILE,
        "123456"))).isTrue();
    // Different keys must produce different MACs, or rotation would be cosmetic.
    assertThat(verifier.matches(issuedUnderV1, verifier.calculate("v2", CHALLENGE, MOBILE,
        "123456"))).isFalse();
  }

  @Test
  @DisplayName("an unknown key version fails loudly rather than rejecting the code")
  void unknownKeyVersionIsAConfigurationFailure() {
    OtpHmac verifier = hmac("v1", "v1:" + key((byte) 7));
    // Must not surface as "wrong code": that would tell a learner their correct code was wrong and
    // hide a retired key from whoever retired it.
    assertThatThrownBy(() -> verifier.calculate("v9", CHALLENGE, MOBILE, "123456"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("v9");
  }

  @Test
  @DisplayName("short, malformed or absent key material is rejected at construction")
  void keyRingIsValidatedAtStartup() {
    byte[] tooShort = new byte[16];
    Arrays.fill(tooShort, (byte) 3);
    assertThatThrownBy(() -> hmac("v1", "v1:" + Base64.getEncoder().encodeToString(tooShort)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("shorter than 32 bytes");

    assertThatThrownBy(() -> hmac("v1", "v1:not-base64!!"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Base64");

    assertThatThrownBy(() -> hmac("v1", "missing-separator"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("version:base64Key");
  }

  @Test
  @DisplayName("an exception about key material never contains the key")
  void keyMaterialNeverAppearsInAFailureMessage() {
    String secret = key((byte) 42);
    byte[] tooShort = new byte[16];
    assertThatThrownBy(() -> hmac("v1", "v1:" + Base64.getEncoder().encodeToString(tooShort)))
        .isInstanceOf(IllegalStateException.class)
        .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(secret,
            Base64.getEncoder().encodeToString(tooShort)));
  }

  @Test
  @DisplayName("an empty ring reports no usable current key rather than throwing")
  void emptyRingIsReportedNotThrown() {
    // Startup validation in RegistrationConfiguration turns this into the operator-facing failure;
    // constructing the bean must not fail first, or a deployment with registration disabled could
    // not start at all.
    OtpHmac verifier = hmac("v1", "");
    assertThat(verifier.hasUsableCurrentKey()).isFalse();
  }

  @Test
  @DisplayName("a ring without the current version is detectable before serving traffic")
  void ringMissingTheCurrentVersionIsDetectable() {
    // Challenges would be stamped v2 and could never be verified. Better to refuse to start.
    OtpHmac verifier = hmac("v2", "v1:" + key((byte) 7));
    assertThat(verifier.hasUsableCurrentKey()).isFalse();
  }
}
