package io.ramals.learningplatform.execution.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Component;

/**
 * Seals and opens the Contract B result envelope defined by M2-ADR-018 §7.
 *
 * <pre>
 *   version (1 byte) | key_id_len (1 byte) | key_id | nonce (12 bytes) | ciphertext+tag
 * </pre>
 *
 * <p>AES-256-GCM, with <strong>the request identity bound as additional authenticated data</strong>.
 * That is the property the format exists for: a ciphertext moved to a different row fails to
 * authenticate rather than decrypting into the wrong learner's record. AAD is authenticated but not
 * encrypted, so binding the identity costs nothing in size and makes relocation detectable rather
 * than silent.
 *
 * <p>Self-describing by design. The key id travels inside the envelope so a stored value can be
 * opened without out-of-band knowledge of how it was produced — which matters precisely because
 * rotation leaves older rows encrypted under retired keys (§8).
 *
 * <p><strong>This class logs nothing.</strong> Not at debug, not on failure. §10 requires that
 * plaintext never reaches a log on any path, and the most reliable way to honour that is to have no
 * logger at all: there is then no statement to review, and no future edit that adds a helpful
 * message can leak one by accident. Failures are raised as exceptions whose messages carry a key id
 * and a reason and nothing else.
 *
 * <p>Every failure path throws. Nothing here returns null, an empty array, or partially decrypted
 * bytes, and there is no branch that proceeds unencrypted (§10).
 */
@Component
public class ResultEnvelopeCodec {

  /** Format version. A stored envelope names its own version so a future format can coexist. */
  static final byte VERSION = 1;

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final int MAX_KEY_ID_BYTES = 255;

  private final ResultEncryptionKeyProvider keys;
  private final SecureRandom random = new SecureRandom();

  public ResultEnvelopeCodec(ResultEncryptionKeyProvider keys) {
    this.keys = keys;
  }

  /**
   * Seals a normalized result under the active key, bound to its request identity.
   *
   * <p>Key resolution happens first and is allowed to throw before any plaintext is touched. §10:
   * key material unavailable at write means <em>refuse to store the result</em> — never store
   * plaintext, and never store unencrypted "temporarily". The refusal is this exception; there is
   * no code path that returns an unsealed value.
   *
   * @throws ResultEncryptionKeyUnavailableException when no usable active key exists
   */
  public SealedResult seal(byte[] plaintext, String requestId) {
    requireRequestId(requestId);
    if (plaintext == null) {
      throw new IllegalArgumentException("a result to seal is required");
    }
    ResultEncryptionKey key = keys.keyFor(keys.activeKeyId());
    byte[] keyIdBytes = key.keyId().getBytes(StandardCharsets.UTF_8);
    if (keyIdBytes.length > MAX_KEY_ID_BYTES) {
      // The length field is one byte. Caught here rather than truncated, because a truncated id
      // would produce an envelope that names a key nobody holds.
      throw new IllegalArgumentException("key id is too long for the envelope format");
    }

    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);

    byte[] sealed;
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key.material(), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(requestId));
      sealed = cipher.doFinal(plaintext);
    } catch (GeneralSecurityException failure) {
      // The cause is dropped: a provider exception can carry buffer content, which here is the
      // learner's diagnosis. The reason names the operation, never the data.
      throw new ResultEnvelopeCorruptException(key.keyId(), "the result could not be sealed");
    }

    return new SealedResult(key.keyId(), ByteBuffer
        .allocate(2 + keyIdBytes.length + NONCE_BYTES + sealed.length)
        .put(VERSION)
        .put((byte) keyIdBytes.length)
        .put(keyIdBytes)
        .put(nonce)
        .put(sealed)
        .array());
  }

  /**
   * Opens an envelope for the request identity it was sealed under.
   *
   * <p>Two failures are kept apart because §10 gives them different responses. A key that cannot be
   * resolved is {@link ResultEncryptionKeyUnavailableException} — refuse to adopt, and <em>never
   * treat an undecryptable result as absent</em>, which would look like a clean re-runnable request
   * and could resubmit to the provider. A tag that does not verify is
   * {@link ResultEnvelopeCorruptException} — treat as corruption, and do not delete the row.
   *
   * @throws ResultEncryptionKeyUnavailableException when the envelope names a key that is not held
   * @throws ResultEnvelopeCorruptException when the envelope is malformed, tampered with, sealed
   *     under a different key, or bound to a different request identity
   */
  public byte[] open(byte[] envelope, String requestId) {
    requireRequestId(requestId);
    if (envelope == null || envelope.length < 2) {
      throw new ResultEnvelopeCorruptException(null, "envelope is truncated");
    }
    if (envelope[0] != VERSION) {
      throw new ResultEnvelopeCorruptException(null, "unsupported envelope version");
    }

    int keyIdLength = envelope[1] & 0xFF;
    if (keyIdLength == 0) {
      throw new ResultEnvelopeCorruptException(null, "envelope names no key");
    }
    // Length-prefix arithmetic is checked before any slice, so a hostile or damaged prefix causes a
    // refusal rather than an index error that a caller might read as something else.
    int nonceStart = 2 + keyIdLength;
    int payloadStart = nonceStart + NONCE_BYTES;
    if (envelope.length <= payloadStart) {
      throw new ResultEnvelopeCorruptException(null, "envelope is truncated");
    }

    String keyId = new String(envelope, 2, keyIdLength, StandardCharsets.UTF_8);
    // Deliberately not caught: an unavailable key must surface as itself, not be flattened into
    // corruption. The two mean different things to an operator.
    ResultEncryptionKey key = keys.keyFor(keyId);

    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key.material(),
          new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(envelope, nonceStart, payloadStart)));
      cipher.updateAAD(aad(requestId));
      return cipher.doFinal(envelope, payloadStart, envelope.length - payloadStart);
    } catch (AEADBadTagException badTag) {
      // The single most important refusal in this class. Reached when the ciphertext was tampered
      // with, sealed under a different key, or bound to a different request identity -- which is
      // what a moved ciphertext is. GCM cannot tell these apart, and neither should the message.
      throw new ResultEnvelopeCorruptException(keyId, "authentication failed");
    } catch (GeneralSecurityException failure) {
      throw new ResultEnvelopeCorruptException(keyId, "the result could not be opened");
    }
  }

  /**
   * The request identity, as authenticated data.
   *
   * <p>Kept in one method so sealing and opening cannot drift apart: an AAD mismatch is
   * indistinguishable from tampering, so two constructions of it would fail as corruption and send
   * an operator looking for an attacker.
   */
  private static byte[] aad(String requestId) {
    return requestId.getBytes(StandardCharsets.UTF_8);
  }

  private static void requireRequestId(String requestId) {
    if (requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("a request identity is required to bind the envelope");
    }
  }
}
