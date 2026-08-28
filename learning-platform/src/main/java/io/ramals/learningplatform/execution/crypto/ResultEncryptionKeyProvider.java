package io.ramals.learningplatform.execution.crypto;

/**
 * The seam between Contract B envelope encryption and wherever key material actually lives.
 *
 * <p>M2-ADR-018 §8 specifies this port and its exact width: <em>"give me the active key id"</em>,
 * and <em>"give me the key material for this key id"</em>. Nothing else. It is deliberately not a
 * crypto service -- it does not encrypt, decrypt, wrap, generate or rotate. A port that performed
 * the operation as well as supplying the key would make substituting a key source mean substituting
 * the cipher too, which is the coupling this exists to prevent.
 *
 * <p><strong>No vendor KMS is wired, and none should be until the platform commits to one</strong>
 * (M2-ADR-018 §8). The initial adapter reads the existing environment-backed secret channel, which
 * is the only mechanism this platform has. This interface is why adopting a KMS later is an adapter
 * rather than a rewrite -- and why it must exist from the first commit rather than be extracted
 * once a second implementation appears.
 *
 * <p>Implementations must <strong>fail closed</strong>. Failure to obtain key material is never a
 * null return, never a fallback to a different key, and never a signal to proceed unencrypted
 * (M2-ADR-018 §10): a Contract B result that cannot be encrypted is not stored, and one that cannot
 * be decrypted is not adopted.
 */
public interface ResultEncryptionKeyProvider {

  /**
   * The key id new results must be encrypted under.
   *
   * <p>Separate from {@link #keyFor(String)} because rotation changes which key is <em>active</em>
   * without retiring the ones already referenced by stored rows. M2-ADR-018 §8 does not re-encrypt
   * on rotation, so the active key and the set of usable keys are different questions and this port
   * answers them separately.
   *
   * @throws ResultEncryptionKeyUnavailableException when no active key is configured
   */
  String activeKeyId();

  /**
   * The key material for one key id, active or retired.
   *
   * <p>Accepts a retired key id on purpose. Rotation leaves historical ciphertext encrypted under
   * older keys, so decryption has to resolve whichever id the envelope names rather than assuming
   * the active one. Retired material stays available until no row references it (M2-ADR-018 §8).
   *
   * @throws ResultEncryptionKeyUnavailableException when the id is unknown, blank, or its material
   *     is unusable. Never returns null, and never substitutes a different key.
   */
  ResultEncryptionKey keyFor(String keyId);
}
