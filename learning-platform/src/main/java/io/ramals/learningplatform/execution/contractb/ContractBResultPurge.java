package io.ramals.learningplatform.execution.contractb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The operator-invoked ceiling sweep.
 *
 * <p>Deliberately separate from {@link ContractBAdoption}, because M2-ADR-019 §3 makes them two
 * mechanisms rather than one grant: an adoption delete is an ordinary-path operation removing
 * exactly the row whose outcome was just committed, while a sweep is an administrative action over a
 * window. Giving both to the same code path would leave no boundary between a targeted delete and
 * an arbitrary bulk one.
 *
 * <p><strong>Nothing in the ordinary Contract B path calls this class.</strong> No controller,
 * service, adapter or reconciliation path references it: adoption removes its own row, and a sweep
 * is never a side effect of driving an execution forward.
 *
 * <p>It is now reachable from exactly one scheduler, {@link ContractBPurgeWorker}, which is
 * separately flagged and off by default. That worker exists because the previous position — ship the
 * function, let an operator run it — left the thirty-day ceiling with no mechanism behind it, and
 * M2-ADR-018 §10 makes results outliving the ceiling a governance failure rather than a backlog. A
 * policy whose only enforcement is someone remembering is the comment pretending to be a control
 * that {@code V023} warned about.
 *
 * <p>The bounds live in the database function, not here. A caller cannot widen the window past the
 * ceiling or narrow it below the floor by calling this method differently, because this method only
 * forwards and the function refuses. Validation in Java as well would create a second copy of the
 * policy, and the copy an operator invoking {@code psql} directly would bypass.
 */
@Component
public class ContractBResultPurge {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContractBResultPurge.class);

  /** The ceiling from M2-ADR-018 §9, chosen against the provider's own 29-day retention. */
  public static final int CEILING_DAYS = 30;

  /** The database function's own default, restated so a bounded call is the ordinary one. */
  static final int DEFAULT_BATCH_LIMIT = 500;

  private final JdbcTemplate jdbc;

  public ContractBResultPurge(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Sweeps at the ceiling. The ordinary operator action. */
  public int sweep() {
    return sweep(CEILING_DAYS);
  }

  /**
   * Removes results that are both beyond the window and belong to a terminal execution.
   *
   * <p>Both conditions, never one. Age alone bounds exposure; the terminal-state test bounds damage,
   * and M2-ADR-019 §4 adds it precisely because a result belonging to a live execution is the
   * artifact a recovery worker is about to adopt. Deleting it would turn a recoverable execution
   * into an unexplained one — a data-loss bug wearing a retention control's clothes.
   *
   * @param retentionDays at least 1 and at most the 30-day ceiling; the function rejects anything
   *     else rather than interpreting it
   * @return rows removed. Zero is the normal steady state and is not an error
   */
  public int sweep(int retentionDays) {
    return sweep(retentionDays, DEFAULT_BATCH_LIMIT);
  }

  /**
   * Sweeps at a window, removing at most {@code batchLimit} rows.
   *
   * <p>The bound is what makes a scheduled sweep safe to leave running. An unbounded delete over an
   * unexpected backlog is one long transaction competing with live traffic; a bounded one drains the
   * same backlog over successive sweeps and never holds the table for longer than a batch.
   *
   * <p>Both bounds are still enforced by the database function rather than here. This method only
   * forwards, so an operator invoking {@code psql} directly meets the same refusals.
   *
   * @param batchLimit rows removed at most, in one sweep
   */
  public int sweep(int retentionDays, int batchLimit) {
    Integer purged;
    try {
      purged = jdbc.queryForObject(
          "SELECT core.purge_expired_ai_execution_results(?, ?)",
          Integer.class, retentionDays, batchLimit);
    } catch (DataAccessException failure) {
      // M2-ADR-018 §10's last row: a purge that cannot run alerts, because results outliving the
      // ceiling is a governance failure rather than a backlog. Logged here and rethrown -- swallowing
      // it would turn a retention breach into silence, which is the one outcome that row forbids.
      // The message names the window and nothing else; a failing sweep has touched no plaintext.
      LOGGER.error("contract B ceiling sweep FAILED and results may now be outliving the "
          + "retention ceiling [retentionDays={}]. This is a governance failure, not a backlog.",
          retentionDays);
      throw failure;
    }
    int removed = purged == null ? 0 : purged;

    if (removed > 0) {
      // M2-ADR-018 §9 calls a non-zero unadopted count a reconciliation-health signal rather than
      // routine: every row here is one that reached the ceiling without anyone adopting it.
      LOGGER.warn("contract B ceiling sweep removed unadopted results "
          + "[removed={}, retentionDays={}]. Unadopted results reaching the ceiling indicate a "
          + "reconciliation problem, not routine cleanup.", removed, retentionDays);
    } else {
      LOGGER.info("contract B ceiling sweep removed nothing [retentionDays={}]", retentionDays);
    }
    return removed;
  }
}
