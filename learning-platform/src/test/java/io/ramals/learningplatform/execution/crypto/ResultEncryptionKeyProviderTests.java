package io.ramals.learningplatform.execution.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M2-ADR-018 criterion 3: the port exists with an environment-backed adapter, and no vendor KMS.
 *
 * <p>Every test here is about a refusal or an identity. The port supplies keys and nothing else, so
 * what is worth proving is that it hands back the right key, keeps versions apart, and fails closed
 * rather than substituting one key for another.
 */
class ResultEncryptionKeyProviderTests {

  private static final String KEY_V1 = "contract-b-key-v1";
  private static final String KEY_V2 = "contract-b-key-v2";

  /** Obviously synthetic, and 32 bytes so it is a structurally valid AES-256 key. */
  private static String material(char fill) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) fill);
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static EnvironmentResultEncryptionKeyProvider provider(
      Map<String, String> keys, String active) {
    ResultEncryptionKeyProperties props = new ResultEncryptionKeyProperties();
    props.setKeys(new LinkedHashMap<>(keys));
    props.setActiveKeyId(active);
    return new EnvironmentResultEncryptionKeyProvider(props);
  }

  // -- 1. the port returns the identity and material the encryption layer needs -------------------

  @Test
  @DisplayName("returns the active key id and usable AES-256 material for it")
  void supplies_identity_and_material() {
    var provider = provider(Map.of(KEY_V1, material('a')), KEY_V1);

    assertThat(provider.activeKeyId()).isEqualTo(KEY_V1);
    ResultEncryptionKey key = provider.keyFor(provider.activeKeyId());
    assertThat(key.keyId()).isEqualTo(KEY_V1);
    assertThat(key.material().getAlgorithm()).isEqualTo("AES");
    assertThat(key.material().getEncoded()).hasSize(32);
  }

  // -- 2. unknown or missing fails closed ---------------------------------------------------------

  @Test
  @DisplayName("an unknown key id fails closed rather than resolving to the active key")
  void unknown_key_fails_closed() {
    var provider = provider(Map.of(KEY_V1, material('a')), KEY_V1);

    assertThatThrownBy(() -> provider.keyFor("contract-b-key-v9"))
        .isInstanceOf(ResultEncryptionKeyUnavailableException.class)
        .hasMessageContaining("no material is configured");
  }

  @Test
  @DisplayName("no configured key at all fails closed on both operations")
  void no_configuration_fails_closed() {
    var empty = provider(Map.of(), "");

    assertThatThrownBy(empty::activeKeyId)
        .isInstanceOf(ResultEncryptionKeyUnavailableException.class);
    assertThatThrownBy(() -> empty.keyFor(KEY_V1))
        .isInstanceOf(ResultEncryptionKeyUnavailableException.class);
  }

  @Test
  @DisplayName("an active id with no material fails closed instead of reporting a usable key")
  void active_id_without_material_fails_closed() {
    assertThatThrownBy(() -> provider(Map.of(KEY_V1, material('a')), KEY_V2).activeKeyId())
        .isInstanceOf(ResultEncryptionKeyUnavailableException.class)
        .hasMessageContaining("has no configured material");
  }

  @Test
  @DisplayName("malformed or wrong-length material is refused, never returned as a key")
  void unusable_material_fails_closed() {
    assertThatThrownBy(() -> provider(Map.of(KEY_V1, "not base64 !!"), KEY_V1).keyFor(KEY_V1))
        .isInstanceOf(ResultEncryptionKeyUnavailableException.class)
        .hasMessageContaining("not valid base64");

    String tooShort = Base64.getEncoder().encodeToString(new byte[16]);
    assertThatThrownBy(() -> provider(Map.of(KEY_V1, tooShort), KEY_V1).keyFor(KEY_V1))
        .isInstanceOf(ResultEncryptionKeyUnavailableException.class)
        .hasMessageContaining("must be 32 bytes");
  }

  // -- 3. key identity is preserved ---------------------------------------------------------------

  @Test
  @DisplayName("the returned key carries the id it was resolved by")
  void identity_is_preserved() {
    var provider = provider(Map.of(KEY_V1, material('a'), KEY_V2, material('b')), KEY_V2);

    assertThat(provider.keyFor(KEY_V1).keyId()).isEqualTo(KEY_V1);
    assertThat(provider.keyFor(KEY_V2).keyId()).isEqualTo(KEY_V2);
  }

  // -- 4. two versions stay distinguishable, for decrypt-old / encrypt-new ------------------------

  @Test
  @DisplayName("a retired key stays resolvable alongside the active one, with distinct material")
  void two_versions_remain_distinguishable() {
    // Rotation per M2-ADR-018 §8: v2 becomes active, v1 is retained because existing rows are not
    // re-encrypted and stay readable only while their key is still held.
    var provider = provider(Map.of(KEY_V1, material('a'), KEY_V2, material('b')), KEY_V2);

    assertThat(provider.activeKeyId()).isEqualTo(KEY_V2);
    ResultEncryptionKey retired = provider.keyFor(KEY_V1);
    ResultEncryptionKey active = provider.keyFor(KEY_V2);

    assertThat(retired.keyId()).isNotEqualTo(active.keyId());
    assertThat(retired.material().getEncoded()).isNotEqualTo(active.material().getEncoded());
    assertThat(retired).isNotEqualTo(active);
  }

  // -- 5. domain code depends on the port, not on an implementation -------------------------------

  @Test
  @DisplayName("the fake exists only in test sources, so no production path can reach it")
  void fake_is_not_reachable_from_production_code() throws Exception {
    Path main = Path.of("src", "main", "java");
    try (Stream<Path> sources = Files.walk(main)) {
      List<Path> leaked =
          sources
              .filter(p -> p.toString().endsWith(".java"))
              .filter(
                  p -> {
                    try {
                      return Files.readString(p, StandardCharsets.UTF_8)
                          .contains("FakeResultEncryptionKeyProvider");
                    } catch (Exception unreadable) {
                      return false;
                    }
                  })
              .toList();
      assertThat(leaked)
          .as("a fake referenced from main sources is a second production implementation")
          .isEmpty();
    }
  }

  @Test
  @DisplayName("the Spring bean is published as the port, so injection points depend on it")
  void bean_is_typed_as_the_port() throws Exception {
    var method =
        ResultEncryptionConfiguration.class.getDeclaredMethod(
            "resultEncryptionKeyProvider", ResultEncryptionKeyProperties.class);
    assertThat(method.getReturnType()).isEqualTo(ResultEncryptionKeyProvider.class);
  }

  @Test
  @DisplayName("no vendor KMS dependency is introduced")
  void no_vendor_kms_dependency() throws Exception {
    Path pkg =
        Path.of("src", "main", "java", "io", "ramals", "learningplatform", "execution", "crypto");
    try (Stream<Path> sources = Files.walk(pkg)) {
      for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        assertThat(text)
            .as("%s must not couple Contract B to a cloud KMS", source.getFileName())
            .doesNotContain("software.amazon")
            .doesNotContain("com.azure")
            .doesNotContain("com.google.cloud")
            .doesNotContain("KeyVault");
      }
    }
  }

  // -- 6. key material is never logged or serialised ----------------------------------------------

  @Test
  @DisplayName("toString on the key, the properties and the failure carries no material")
  void material_never_appears_in_string_forms() {
    String secret = material('z');
    ResultEncryptionKeyProperties props = new ResultEncryptionKeyProperties();
    props.setKeys(Map.of(KEY_V1, secret));
    props.setActiveKeyId(KEY_V1);
    ResultEncryptionKey key = new EnvironmentResultEncryptionKeyProvider(props).keyFor(KEY_V1);

    assertThat(key.toString()).doesNotContain(secret).contains("REDACTED", KEY_V1);
    assertThat(props.toString()).doesNotContain(secret).contains("REDACTED", KEY_V1);

    // The base64 decoder's own message echoes the offending input; the adapter must not propagate
    // it, or malformed key material would reach a log through an exception message.
    ResultEncryptionKeyProperties bad = new ResultEncryptionKeyProperties();
    bad.setKeys(Map.of(KEY_V1, "AAAA-not-base64-" + secret));
    assertThatThrownBy(() -> new EnvironmentResultEncryptionKeyProvider(bad).keyFor(KEY_V1))
        .hasMessageNotContaining(secret)
        .hasNoCause();
  }

  @Test
  @DisplayName("no key material is committed as a default or fixture")
  void repository_ships_no_key() throws Exception {
    ResultEncryptionKeyProperties defaults = new ResultEncryptionKeyProperties();
    assertThat(defaults.getActiveKeyId()).isEmpty();
    assertThat(defaults.getKeys()).isEmpty();

    // Asserted against the encryption prefix rather than the whole `contract-b:` block. The block
    // now carries Contract B's feature flags, which are not key material and must be committed --
    // a default nobody can read is a default nobody can review. What must never appear is anything
    // under ramals.contract-b.encryption, which is where ResultEncryptionKeyProperties binds.
    Path config = Path.of("src", "main", "resources", "application.yml");
    String yaml = Files.readString(config, StandardCharsets.UTF_8);
    assertThat(yaml)
        .as("no Contract B key may be configured in the repository")
        .doesNotContain("encryption:")
        .doesNotContain("active-key-id")
        .doesNotContain("keys:");
  }

  // -- 7. failure never falls back ----------------------------------------------------------------

  @Test
  @DisplayName("a failing lookup yields no key at all, never a substitute or a plaintext path")
  void failure_never_falls_back() {
    var provider = provider(Map.of(KEY_V1, material('a'), KEY_V2, material('b')), KEY_V2);

    // The named id is unknown. The active key exists and is *not* substituted for it -- doing so
    // would decrypt one key's ciphertext under another and fail later, where the cause is hidden.
    assertThatThrownBy(() -> provider.keyFor("contract-b-key-retired"))
        .isInstanceOf(ResultEncryptionKeyUnavailableException.class)
        .extracting(e -> ((ResultEncryptionKeyUnavailableException) e).keyId())
        .isEqualTo("contract-b-key-retired");

    // And the port has no operation that could report success without a key: every path either
    // returns a key carrying its own id, or throws.
    assertThatCode(() -> provider.keyFor(KEY_V1)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("the fake fails closed the same way, so tests cannot pass on absent behaviour")
  void fake_fails_closed_like_the_adapter() {
    var fake = new FakeResultEncryptionKeyProvider().with(KEY_V1).with(KEY_V2).active(KEY_V2);

    assertThat(fake.activeKeyId()).isEqualTo(KEY_V2);
    assertThat(fake.keyFor(KEY_V1).material().getEncoded())
        .isNotEqualTo(fake.keyFor(KEY_V2).material().getEncoded());
    assertThatThrownBy(() -> fake.keyFor("nope"))
        .isInstanceOf(ResultEncryptionKeyUnavailableException.class);
    assertThatThrownBy(() -> new FakeResultEncryptionKeyProvider().activeKeyId())
        .isInstanceOf(ResultEncryptionKeyUnavailableException.class);
  }
}
