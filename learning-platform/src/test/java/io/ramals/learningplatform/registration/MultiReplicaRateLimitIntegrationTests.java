package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The registration abuse ceiling across separate application instances.
 *
 * <p>Separate JDBC connections inside one JVM do not demonstrate this: they already share a heap, so
 * an in-memory limiter would pass that test while failing in production the moment a request landed
 * on a second pod. Each replica here is a real child JVM with its own heap, its own repository and
 * its own connection pool, sharing only PostgreSQL.
 *
 * <p>This is one step short of pod-level: the replicas are processes on one host rather than
 * containers behind a service, so it does not exercise the ingress, the service mesh or per-pod
 * configuration drift. It does exercise the only thing the ceiling depends on for correctness, which
 * is that the counter lives in shared storage. Pod-level remains documented rather than automated —
 * see {@code docs/architecture} and the PR record.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class MultiReplicaRateLimitIntegrationTests {

  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";

  private static final int REPLICAS = 4;
  private static final int LIMIT = 10;
  private static final int ATTEMPTS_PER_REPLICA = 10;
  private static final int WINDOW_SECONDS = 3600;

  /**
   * Self-contained bootstrap.
   *
   * <p>The child JVMs connect as the least-privileged runtime role, so this suite has to provision
   * the roles and schema itself rather than inherit them from whichever suite happened to run first.
   */
  @org.junit.jupiter.api.BeforeAll
  static void migrateAsRuntimeAndMigrationRoles() throws Exception {
    String databaseUrl = required("RAMALS_TEST_POSTGRES_URL");
    String adminUser = required("RAMALS_TEST_POSTGRES_ADMIN_USER");
    try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
            databaseUrl, adminUser, required("RAMALS_TEST_POSTGRES_ADMIN_PASSWORD"));
        java.sql.Statement statement = connection.createStatement()) {
      statement.execute("""
          DO $$
          BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_core_migration') THEN
              CREATE ROLE ramals_core_migration LOGIN PASSWORD 'm0-t05-migration-test';
            ELSE
              ALTER ROLE ramals_core_migration WITH LOGIN PASSWORD 'm0-t05-migration-test';
            END IF;
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_core_runtime') THEN
              CREATE ROLE ramals_core_runtime LOGIN PASSWORD 'm0-t05-runtime-test';
            ELSE
              ALTER ROLE ramals_core_runtime WITH LOGIN PASSWORD 'm0-t05-runtime-test';
            END IF;
          END
          $$;
          """);
      String quotedDatabase = statement.enquoteIdentifier(currentDatabase(statement), true);
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO "
          + statement.enquoteIdentifier(adminUser, true));
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit, identity CASCADE");
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO " + MIGRATION_USER);
      statement.execute("REVOKE CONNECT ON DATABASE " + quotedDatabase + " FROM PUBLIC");
      statement.execute("GRANT CONNECT ON DATABASE " + quotedDatabase + " TO "
          + MIGRATION_USER + ", " + RUNTIME_USER);
    }

    org.flywaydb.core.Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit", "identity")
        .createSchemas(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  private static String currentDatabase(java.sql.Statement statement) throws java.sql.SQLException {
    try (java.sql.ResultSet result = statement.executeQuery("SELECT current_database()")) {
      result.next();
      return result.getString(1);
    }
  }

  @Test
  @DisplayName("four separate JVMs share one ceiling; the total allowed never exceeds the limit")
  void ceilingIsSharedAcrossSeparateProcesses() throws Exception {
    String jdbcUrl = required("RAMALS_TEST_POSTGRES_URL");
    String dimension = "multi-replica:" + UUID.randomUUID();

    ExecutorService pool = Executors.newFixedThreadPool(REPLICAS);
    try {
      List<Callable<Integer>> replicas = new ArrayList<>();
      for (int replica = 0; replica < REPLICAS; replica++) {
        replicas.add(() -> runReplica(jdbcUrl, dimension));
      }
      List<Future<Integer>> results = pool.invokeAll(replicas, 4, TimeUnit.MINUTES);

      int totalAllowed = 0;
      for (Future<Integer> result : results) {
        totalAllowed += result.get();
      }

      // 4 replicas x 10 attempts = 40 requests against a ceiling of 10. A per-instance limiter would
      // allow 40; a shared one allows 10.
      assertThat(totalAllowed)
          .as("%d replicas must share one ceiling of %d", REPLICAS, LIMIT)
          .isEqualTo(LIMIT);
    } finally {
      pool.shutdownNow();
    }
  }

  private static int runReplica(String jdbcUrl, String dimension) throws Exception {
    List<String> command = new ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"),
        RateLimitReplicaProbe.class.getName(),
        jdbcUrl, RUNTIME_USER, RUNTIME_PASSWORD, dimension,
        String.valueOf(LIMIT), String.valueOf(WINDOW_SECONDS),
        String.valueOf(ATTEMPTS_PER_REPLICA)));

    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    int allowed = -1;
    StringBuilder output = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line).append('\n');
        if (line.startsWith("ALLOWED=")) {
          allowed = Integer.parseInt(line.substring("ALLOWED=".length()).trim());
        }
      }
    }
    assertThat(process.waitFor(4, TimeUnit.MINUTES)).as("replica must terminate").isTrue();
    assertThat(allowed).as("replica did not report a result:%n%s", output).isNotNegative();
    return allowed;
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set for the multi-replica suite.");
    }
    return value;
  }
}
