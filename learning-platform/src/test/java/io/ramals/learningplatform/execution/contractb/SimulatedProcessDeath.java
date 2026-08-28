package io.ramals.learningplatform.execution.contractb;

/**
 * A process death, modelled as precisely as a single JVM allows.
 *
 * <p>An {@link Error} rather than an exception, and that is the whole design. Every
 * {@code catch (RuntimeException ...)} in the lifecycle exists to classify a <em>failure</em>, and a
 * process death is not a failure — it is the absence of any further execution. Throwing something
 * catchable would test the error handling and prove nothing about recovery: the service would
 * dutifully record an outcome, which is exactly what a dead process cannot do.
 *
 * <p>So this unwinds through every handler and out of the caller, leaving behind precisely what had
 * been committed to PostgreSQL at that instant. That surviving state is the entire subject of the
 * qualification, and the only thing a replacement process gets to see.
 *
 * <p>What this cannot model: a partial write inside one statement, or a commit that reached the
 * database while the acknowledgement was lost on the way back. The first is prevented by PostgreSQL;
 * the second is a real window and is qualified through the lost-acknowledgement kill points rather
 * than by pretending this class covers it.
 */
public final class SimulatedProcessDeath extends Error {

  private static final long serialVersionUID = 1L;

  public SimulatedProcessDeath(String at) {
    super("simulated process death at " + at);
  }
}
