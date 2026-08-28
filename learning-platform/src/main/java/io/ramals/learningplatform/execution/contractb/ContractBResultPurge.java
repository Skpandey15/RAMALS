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
 * <p><strong>Nothing in the ordinary Contract B path calls this class.</strong> That is a testable
 * property and is tested: no controller, service, adapter, scheduler or reconciliation path
 * references it. The platform still has no scheduler — {@code V023}'s judgement stands, that
 * shipping a function an operator can run and can test is honest, and that a policy with no
 * mechanism is a comment pretending to be a control.
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
    Integer purged;
    try {
      purged = jdbc.queryForObject(
          "SELECT core.purge_expired_ai_execution_results(?)", Integer.class, retentionDays);
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
