package io.ramals.learningplatform.registration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * One simulated application replica, for {@link MultiReplicaRateLimitIntegrationTests}.
 *
 * <p>Runs in its own JVM, so it has its own heap and its own {@code RegistrationRepository}. Nothing
 * is shared with the other replicas except the database — which is the point: if the ceiling ever
 * regressed to in-memory state, each of these would get a full allowance and the total would exceed
 * the limit.
 *
 * <p>Prints the number of requests it was allowed, so the parent can sum across replicas.
 *
 * <p>Args: jdbcUrl user password dimension limit windowSeconds attempts
 */
public final class RateLimitReplicaProbe {

  private RateLimitReplicaProbe() {
  }

  public static void main(String[] args) {
    String jdbcUrl = args[0];
    String user = args[1];
    String password = args[2];
    String dimension = args[3];
    int limit = Integer.parseInt(args[4]);
    int windowSeconds = Integer.parseInt(args[5]);
    int attempts = Integer.parseInt(args[6]);

    RegistrationRepository repository =
        new RegistrationRepository(new JdbcTemplate(
            new DriverManagerDataSource(jdbcUrl, user, password)));

    int allowed = 0;
    for (int attempt = 0; attempt < attempts; attempt++) {
      if (repository.withinCeiling(dimension, limit, windowSeconds)) {
        allowed++;
      }
    }
    System.out.println("ALLOWED=" + allowed);
  }
}
