package io.ramals.learningplatform.execution.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * M2-ADR-018 criteria 4, 5 and 9 for the Contract B result envelope.
 *
 * <p>Criterion 4 is the format and the moved-ciphertext proof; criterion 5 is every crypto row of
 * §10 failing closed; criterion 9 is that no plaintext or key material reaches a log on any path,
 * success or failure.
 */
class ResultEnvelopeCodecTests {

  private static final String KEY_V1 = "contract-b-key-v1";
  private static final String KEY_V2 = "contract-b-key-v2";
  private static final String REQUEST = "wf-diag-01900000-0000-7000-8000-000000000001";
  private static final String OTHER_REQUEST = "wf-diag-01900000-0000-7000-8000-000000000002";

  /** Stands in for a normalized diagnostic proposal. Distinctive so a leak is unmistakable. */
  private static final String PLAINTEXT =
      "{\"contractVersion\":\"diagnostic-proposal.v1\",\"diagnoses\":[\"CANARY-LEARNER-DIAGNOSIS\"]}";

  private FakeResultEncryptionKeyProvider keys;
  private ResultEnvelopeCodec codec;
  private ListAppender<ILoggingEvent> logs;
  private Logger rootLogger;
  private Level originalLevel;

  @BeforeEach
  void setUp() {
    keys = new FakeResultEncryptionKeyProvider().with(KEY_V1).with(KEY_V2).active(KEY_V1);
    codec = new ResultEnvelopeCodec(keys);

    // Attached to the ROOT logger at TRACE, so anything logged anywhere during these operations is
    // captured -- including by the JCE provider or a future logger added to the codec.
    rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    logs = new ListAppender<>();
    logs.start();
    rootLogger.addAppender(logs);
    originalLevel = rootLogger.getLevel();
    rootLogger.setLevel(Level.TRACE);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(logs);
    rootLogger.setLevel(originalLevel);
  }

  private byte[] plaintext() {
    return PLAINTEXT.getBytes(StandardCharsets.UTF_8);
  }

  private String capturedLogs() {
    return logs.list.stream()
        .map(event -> event.getFormattedMessage() + " " + Arrays.toString(event.getArgumentArray()))
        .reduce("", (a, b) -> a + "\n" + b);
  }

  // -- criterion 4: the envelope round-trips and has the ADR-defined shape ------------------------

  @Test
  @DisplayName("a sealed result opens back to the same bytes under the same request identity")
  void round_trips() {
    SealedResult sealed = codec.seal(plaintext(), REQUEST);

    assertThat(sealed.keyId()).isEqualTo(KEY_V1);
    assertThat(codec.open(sealed.envelope(), REQUEST)).isEqualTo(plaintext());
  }

  @Test
  @DisplayName("the envelope is self-describing: version, key id length, key id, nonce, payload")
  void envelope_has_the_adr_defined_shape() {
    byte[] envelope = codec.seal(plaintext(), REQUEST).envelope();
    byte[] keyIdBytes = KEY_V1.getBytes(StandardCharsets.UTF_8);

    assertThat(envelope[0]).isEqualTo(ResultEnvelopeCodec.VERSION);
    assertThat(envelope[1] & 0xFF).isEqualTo(keyIdBytes.length);
    assertThat(Arrays.copyOfRange(envelope, 2, 2 + keyIdBytes.length)).isEqualTo(keyIdBytes);
    // 2 header bytes + key id + 12-byte nonce + ciphertext + 16-byte GCM tag.
    assertThat(envelope).hasSize(2 + keyIdBytes.length + 12 + plaintext().length + 16);
  }

  @Test
  @DisplayName("the ciphertext does not contain the plaintext")
  void ciphertext_does_not_contain_plaintext() {
    byte[] envelope = codec.seal(plaintext(), REQUEST).envelope();

    assertThat(new String(envelope, StandardCharsets.ISO_8859_1))
        .doesNotContain("CANARY-LEARNER-DIAGNOSIS")
        .doesNotContain("diagnostic-proposal.v1");
  }

  @Test
  @DisplayName("sealing the same result twice produces different envelopes")
  void nonce_is_not_reused() {
    // Nonce reuse under GCM is catastrophic, not cosmetic: it leaks the XOR of two plaintexts and
    // can expose the authentication subkey.
    byte[] first = codec.seal(plaintext(), REQUEST).envelope();
    byte[] second = codec.seal(plaintext(), REQUEST).envelope();

    assertThat(first).isNotEqualTo(second);
  }

  // -- criterion 4: the moved-ciphertext proof ---------------------------------------------------

  @Test
  @DisplayName("a ciphertext moved to a different row fails to authenticate")
  void moved_ciphertext_fails_to_authenticate() {
    // The property the AAD binding exists for. Without it this decrypts cleanly into the wrong
    // learner's record, and nothing downstream can tell.
    byte[] envelope = codec.seal(plaintext(), REQUEST).envelope();

    assertThatThrownBy(() -> codec.open(envelope, OTHER_REQUEST))
        .isInstanceOf(ResultEnvelopeCorruptException.class)
        .hasMessageContaining("authentication failed");
  }

  // -- criterion 5: every crypto row of §10 fails closed -----------------------------------------

  @Test
  @DisplayName("tampered ciphertext fails to authenticate rather than decrypting")
  void tampered_ciphertext_is_refused() {
    byte[] envelope = codec.seal(plaintext(), REQUEST).envelope();
    envelope[envelope.length - 1] ^= 0x01;

    assertThatThrownBy(() -> codec.open(envelope, REQUEST))
        .isInstanceOf(ResultEnvelopeCorruptException.class)
        .hasMessageContaining("authentication failed");
  }

  @Test
  @DisplayName("a tampered nonce is refused")
  void tampered_nonce_is_refused() {
    byte[] envelope = codec.seal(plaintext(), REQUEST).envelope();
    int nonceStart = 2 + KEY_V1.getBytes(StandardCharsets.UTF_8).length;
    envelope[nonceStart] ^= 0x01;

    assertThatThrownBy(() -> codec.open(envelope, REQUEST))
        .isInstanceOf(ResultEnvelopeCorruptException.class);
  }

  @Test
  @DisplayName("an envelope opened under a different key fails to authenticate")
  void wrong_key_is_refused() {
    // Sealed under v2, then relabelled to name v1. Both ids are the same length, so the envelope
    // stays structurally valid and the only thing wrong is which key it points at -- the shape a
    // mis-provisioned or mixed-up rotation takes. v1 is held, so this reaches the cipher and is
    // refused by the tag rather than by a missing key.
    byte[] envelope = new ResultEnvelopeCodec(keys.active(KEY_V2)).seal(plaintext(), REQUEST)
        .envelope();
    byte[] v1 = KEY_V1.getBytes(StandardCharsets.UTF_8);
    assertThat(v1).hasSize(KEY_V2.getBytes(StandardCharsets.UTF_8).length);
    byte[] relabelled = envelope.clone();
    System.arraycopy(v1, 0, relabelled, 2, v1.length);

    assertThatThrownBy(() -> codec.open(relabelled, REQUEST))
        .isInstanceOf(ResultEnvelopeCorruptException.class)
        .hasMessageContaining("authentication failed");
  }

  @Test
  @DisplayName("key material unavailable at write refuses to seal, and never returns plaintext")
  void missing_key_at_write_fails_closed() {
    var noKeys = new ResultEnvelopeCodec(new FakeResultEncryptionKeyProvider());

    assertThatThrownBy(() -> noKeys.seal(plaintext(), REQUEST))
        .isInstanceOf(ResultEncryptionKeyUnavailableException.class);
  }

  @Test
  @DisplayName("key unavailable at read is reported as unavailable, never as absent or corrupt")
  void missing_key_at_read_fails_closed_distinctly() {
    byte[] envelope = codec.seal(plaintext(), REQUEST).envelope();
    // §10: never treat an undecryptable result as absent -- that would look like a clean
    // re-runnable request and could resubmit to the provider. It must also not be flattened into
    // corruption, which would tell an operator the row is damaged when the key is merely missing.
    var withoutKey = new ResultEnvelopeCodec(new FakeResultEncryptionKeyProvider().with(KEY_V2));

    assertThatThrownBy(() -> withoutKey.open(envelope, REQUEST))
        .isInstanceOf(ResultEncryptionKeyUnavailableException.class)
        .isNotInstanceOf(ResultEnvelopeCorruptException.class);
  }

  @Test
  @DisplayName("malformed envelopes are refused rather than partially read")
  void malformed_envelopes_are_refused() {
    byte[] good = codec.seal(plaintext(), REQUEST).envelope();

    assertThatThrownBy(() -> codec.open(null, REQUEST))
        .isInstanceOf(ResultEnvelopeCorruptException.class);
    assertThatThrownBy(() -> codec.open(new byte[0], REQUEST))
        .isInstanceOf(ResultEnvelopeCorruptException.class);
    assertThatThrownBy(() -> codec.open(Arrays.copyOf(good, 8), REQUEST))
        .isInstanceOf(ResultEnvelopeCorruptException.class)
        .hasMessageContaining("truncated");

    byte[] badVersion = good.clone();
    badVersion[0] = 99;
    assertThatThrownBy(() -> codec.open(badVersion, REQUEST))
        .isInstanceOf(ResultEnvelopeCorruptException.class)
        .hasMessageContaining("unsupported envelope version");

    byte[] noKeyId = good.clone();
    noKeyId[1] = 0;
    assertThatThrownBy(() -> codec.open(noKeyId, REQUEST))
        .isInstanceOf(ResultEnvelopeCorruptException.class)
        .hasMessageContaining("names no key");

    // A length prefix larger than the envelope must be refused, not indexed past the end.
    byte[] overlongPrefix = good.clone();
    overlongPrefix[1] = (byte) 250;
    assertThatThrownBy(() -> codec.open(overlongPrefix, REQUEST))
        .isInstanceOf(ResultEnvelopeCorruptException.class);
  }

  @Test
  @DisplayName("a missing request identity is refused on both operations")
  void request_identity_is_required() {
    assertThatThrownBy(() -> codec.seal(plaintext(), " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.open(new byte[64], null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // -- criterion 9: nothing reaches a log, on any path -------------------------------------------

  @Test
  @DisplayName("negative control: the capture would see a leak if one occurred")
  void log_capture_is_capable_of_failing() {
    // Without this, the two tests below could pass because the appender captures nothing at all --
    // proving the harness is inert rather than proving the codec is quiet. Here something does log
    // the canary, and the same assertion those tests use must catch it.
    LoggerFactory.getLogger(ResultEnvelopeCodecTests.class).info("leak {}", PLAINTEXT);

    assertThat(capturedLogs()).contains("CANARY-LEARNER-DIAGNOSIS");
  }

  @Test
  @DisplayName("no plaintext or key material is logged on the success path")
  void success_path_logs_nothing_sensitive() {
    SealedResult sealed = codec.seal(plaintext(), REQUEST);
    codec.open(sealed.envelope(), REQUEST);

    assertThat(capturedLogs())
        .doesNotContain("CANARY-LEARNER-DIAGNOSIS")
        .doesNotContain(PLAINTEXT)
        .doesNotContain(Base64.getEncoder().encodeToString(
            keys.keyFor(KEY_V1).material().getEncoded()));
  }

  @Test
  @DisplayName("no plaintext or key material is logged on any failure path")
  void failure_paths_log_nothing_sensitive() {
    byte[] envelope = codec.seal(plaintext(), REQUEST).envelope();
    byte[] tampered = envelope.clone();
    tampered[tampered.length - 1] ^= 0x01;

    // Every failure the codec can raise, driven in turn.
    swallow(() -> codec.open(envelope, OTHER_REQUEST));
    swallow(() -> codec.open(tampered, REQUEST));
    swallow(() -> codec.open(Arrays.copyOf(envelope, 8), REQUEST));
    swallow(() -> new ResultEnvelopeCodec(new FakeResultEncryptionKeyProvider())
        .seal(plaintext(), REQUEST));
    swallow(() -> new ResultEnvelopeCodec(new FakeResultEncryptionKeyProvider().with(KEY_V2))
        .open(envelope, REQUEST));

    assertThat(capturedLogs())
        .doesNotContain("CANARY-LEARNER-DIAGNOSIS")
        .doesNotContain(PLAINTEXT);
  }

  @Test
  @DisplayName("exception messages carry a key id and a reason, never plaintext or material")
  void exception_messages_carry_no_sensitive_content() {
    byte[] envelope = codec.seal(plaintext(), REQUEST).envelope();
    String material = Base64.getEncoder()
        .encodeToString(keys.keyFor(KEY_V1).material().getEncoded());

    assertThatThrownBy(() -> codec.open(envelope, OTHER_REQUEST))
        .hasMessageNotContaining("CANARY-LEARNER-DIAGNOSIS")
        .hasMessageNotContaining(material)
        .hasMessageContaining(KEY_V1)
        .hasNoCause();
  }

  @Test
  @DisplayName("the codec declares no logger, so a future edit cannot leak through one")
  void codec_has_no_logger() {
    // The strongest form of criterion 9 for this class: there is no logger to misuse. A message
    // added later would have to add the field too, which is visible in review.
    assertThat(Arrays.stream(ResultEnvelopeCodec.class.getDeclaredFields())
        .map(field -> field.getType().getName())
        .anyMatch(name -> name.contains("Logger")))
        .as("ResultEnvelopeCodec must not hold a logger")
        .isFalse();
  }

  @Test
  @DisplayName("the sealed value's string form reveals only its length")
  void sealed_result_to_string_is_safe() {
    SealedResult sealed = codec.seal(plaintext(), REQUEST);

    assertThat(sealed.toString())
        .contains(KEY_V1)
        .contains("envelopeBytes=")
        .doesNotContain("CANARY-LEARNER-DIAGNOSIS");
  }

  private static void swallow(Runnable operation) {
    try {
      operation.run();
    } catch (RuntimeException expected) {
      // Each of these is asserted individually elsewhere; here only the logs matter.
    }
  }
}
