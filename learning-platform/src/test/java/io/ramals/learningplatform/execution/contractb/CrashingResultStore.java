package io.ramals.learningplatform.execution.contractb;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import io.ramals.learningplatform.execution.crypto.ResultEnvelopeCodec;

/**
 * A result store that can die on either side of the write.
 *
 * <p>The two sides are the difference between kill point 7 and kill point 8, and they leave opposite
 * evidence. Dying <em>before</em> the write leaves no ciphertext, so a replacement must retrieve
 * again. Dying <em>after</em> it leaves a committed row that a replacement will try to write a second
 * time — which is where a store that is not idempotent turns a recoverable execution into a crash
 * loop.
 *
 * <p>Extends the real store rather than reimplementing it, so both paths run the genuine validation,
 * sealing and insert. A fake here would prove that a fake is idempotent.
 */
public final class CrashingResultStore extends ContractBResultStore {

  /** Where the process dies, relative to the encrypted write. */
  public enum When {
    NEVER,
    /** After the result is in memory, before any ciphertext is committed. */
    BEFORE_WRITE,
    /** After the ciphertext is committed, before the execution is marked terminal. */
    AFTER_WRITE
  }

  private When when = When.NEVER;

  public CrashingResultStore(JdbcTemplate jdbc, ResultEnvelopeCodec codec, ObjectMapper json) {
    super(jdbc, codec, json);
  }

  public CrashingResultStore dieAt(When when) {
    this.when = when;
    return this;
  }

  public CrashingResultStore survive() {
    this.when = When.NEVER;
    return this;
  }

  @Override
  public StoredResult store(String requestId, String providerExecutionId, String normalizedJson) {
    if (when == When.BEFORE_WRITE) {
      throw new SimulatedProcessDeath("BEFORE_RESULT_WRITE");
    }
    StoredResult stored = super.store(requestId, providerExecutionId, normalizedJson);
    if (when == When.AFTER_WRITE) {
      // The ciphertext is committed. Everything the caller would have done next -- the ledger entry,
      // the terminal transition -- never happens.
      throw new SimulatedProcessDeath("AFTER_RESULT_WRITE");
    }
    return stored;
  }
}
