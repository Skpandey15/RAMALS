package io.ramals.learningplatform.assessment.misconceptiongraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.MisconceptionRepository;
import io.ramals.learningplatform.assessment.MisconceptionTargetType;
import io.ramals.learningplatform.observability.UuidV7;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * M2-ADR-033 step 2 against real PostgreSQL and the real, already-seeded KAFKA v2 acks curriculum --
 * the same harness {@code MisconceptionRelationshipGraphPersistenceIntegrationTests} (step 1) uses.
 *
 * <p>Covers target resolution for all three kinds, published-only filtering enforced in SQL, the
 * bounded external-neighbour rule ("edges among, and from, that set"), symmetry / direction
 * rendering, the fixed O(1) query count, determinism, empty-state semantics, and that a query
 * mutates nothing. No runtime selector is exercised -- none reads the graph (§6/§15).
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class MisconceptionGraphQuerySurfaceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  // Real, already-seeded content (V057 + V061).
  private static final UUID ACK_DURABILITY_OBJECTIVE =
      UUID.fromString("01900000-0000-7000-8000-000000000d11");
  private static final UUID ACK_DURABILITY_CONCEPT =
      UUID.fromString("01900000-0000-7000-8000-000000000f01");
  private static final UUID ACK_DURABILITY_SUB_CONCEPT =
      UUID.fromString("01900000-0000-7000-8000-000000000f02");
  private static final UUID MISCONCEPTION_ACKS_ALL_ALONE = // targets the SUB_CONCEPT
      UUID.fromString("01900000-0000-7000-8000-000000000f03");
  private static final UUID MISCONCEPTION_ACKS_1_VS_ALL = // targets the OBJECTIVE directly
      UUID.fromString("01900000-0000-7000-8000-000000000f04");
  private static final UUID SEED_RELATED_EDGE =
      UUID.fromString("01900000-0000-7000-8000-000000000f10");
  private static final UUID SEED_PREREQUISITE_LINK =
      UUID.fromString("01900000-0000-7000-8000-000000000f20");
  private static final UUID KAFKA_BROKER_SKILL =
      UUID.fromString("01900000-0000-7000-8000-000000000101");

  private static String databaseUrl;
  private JdbcTemplate runtimeJdbc;
  private CountingJdbcTemplate countingJdbc;
  private MisconceptionRepository misconceptions;
  private MisconceptionRelationshipService writeService;
  private MisconceptionGraphQueryService queryService;
  private MisconceptionGraphQueryService countingQueryService;

  @BeforeAll
  static void migrate() throws SQLException {
    databaseUrl = requiredEnvironment("RAMALS_TEST_POSTGRES_URL");
    String adminUser = requiredEnvironment("RAMALS_TEST_POSTGRES_ADMIN_USER");
    try (Connection connection = DriverManager.getConnection(
            databaseUrl, adminUser, requiredEnvironment("RAMALS_TEST_POSTGRES_ADMIN_PASSWORD"));
        Statement statement = connection.createStatement()) {
      String quotedDatabase = statement.enquoteIdentifier(currentDatabase(statement), true);
      String quotedAdmin = statement.enquoteIdentifier(adminUser, true);
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
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO " + quotedAdmin);
      statement.execute("DROP SCHEMA IF EXISTS core, ledger, audit, identity CASCADE");
      statement.execute("ALTER DATABASE " + quotedDatabase + " OWNER TO " + MIGRATION_USER);
      statement.execute("REVOKE CONNECT ON DATABASE " + quotedDatabase + " FROM PUBLIC");
      statement.execute("GRANT CONNECT ON DATABASE " + quotedDatabase + " TO "
          + MIGRATION_USER + ", " + RUNTIME_USER);
    }

    Flyway.configure()
        .dataSource(databaseUrl, MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .defaultSchema("core")
        .schemas("core", "ledger", "audit", "identity")
        .createSchemas(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  private void wire() {
    if (queryService == null) {
      runtimeJdbc = new JdbcTemplate(
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD));
      countingJdbc = new CountingJdbcTemplate(
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD));
      misconceptions = new MisconceptionRepository(runtimeJdbc);
      MisconceptionRelationshipRepository writeRepo =
          new MisconceptionRelationshipRepository(runtimeJdbc);
      writeService = new MisconceptionRelationshipService(
          writeRepo, new MisconceptionRelationshipValidator(writeRepo));
      queryService = new MisconceptionGraphQueryService(
          new MisconceptionGraphQueryRepository(runtimeJdbc));
      countingQueryService = new MisconceptionGraphQueryService(
          new MisconceptionGraphQueryRepository(countingJdbc));
    }
  }

  private UUID freshPublishedMisconceptionOnObjective(String name) {
    UUID id = UuidV7.generate();
    misconceptions.insertTargetingObjective(id, name, "step-2 fixture", ACK_DURABILITY_OBJECTIVE);
    misconceptions.publish(id);
    return id;
  }

  private UUID freshPublishedMisconceptionOnSubConcept(String name) {
    UUID id = UuidV7.generate();
    misconceptions.insertTargetingNode(id, name, "step-2 fixture", ACK_DURABILITY_SUB_CONCEPT);
    misconceptions.publish(id);
    return id;
  }

  private MisconceptionGraphView graphFor(MisconceptionTargetType kind, UUID id) {
    return queryService.graphFor(new MisconceptionGraphTarget(kind, id));
  }

  // -- target resolution, all three kinds ---------------------------------------------------------

  @Test
  @DisplayName("a LEARNING_OBJECTIVE target resolves to its curriculum context and direct misconceptions")
  void objectiveTargetResolves() {
    wire();
    MisconceptionGraphView view =
        graphFor(MisconceptionTargetType.LEARNING_OBJECTIVE, ACK_DURABILITY_OBJECTIVE);

    assertThat(view.target().kind()).isEqualTo(MisconceptionTargetType.LEARNING_OBJECTIVE);
    assertThat(view.target().id()).isEqualTo(ACK_DURABILITY_OBJECTIVE);
    assertThat(view.target().objectiveId()).isEqualTo(ACK_DURABILITY_OBJECTIVE);
    assertThat(view.target().owningSkillCode()).isEqualTo("KAFKA_PRODUCER_ACKS");
    assertThat(view.target().domainCode()).isEqualTo("KAFKA");

    assertThat(view.prerequisites())
        .extracting(CurriculumPrerequisiteView::prerequisiteSkillCode)
        .contains("KAFKA_BROKER");

    // ...0f04 targets the objective directly; ...0f03 targets the sub-concept, so it is absent.
    // (Other tests in this shared-DB class add their own published misconceptions on the objective,
    // so this asserts membership + structure, not an exact set.)
    assertThat(view.misconceptions())
        .extracting(PublishedMisconceptionView::id)
        .contains(MISCONCEPTION_ACKS_1_VS_ALL)
        .doesNotContain(MISCONCEPTION_ACKS_ALL_ALONE);
    assertThat(view.misconceptions()).allSatisfy(mc -> {
      assertThat(mc.targetKind()).isEqualTo(MisconceptionTargetType.LEARNING_OBJECTIVE);
      assertThat(mc.targetId()).isEqualTo(ACK_DURABILITY_OBJECTIVE);
    });
  }

  @Test
  @DisplayName("a CONCEPT target resolves; nothing targets the concept directly so its set is empty")
  void conceptTargetResolvesWithEmptyMisconceptionSet() {
    wire();
    MisconceptionGraphView view =
        graphFor(MisconceptionTargetType.CONCEPT, ACK_DURABILITY_CONCEPT);

    assertThat(view.target().kind()).isEqualTo(MisconceptionTargetType.CONCEPT);
    assertThat(view.target().objectiveId()).isEqualTo(ACK_DURABILITY_OBJECTIVE);
    assertThat(view.prerequisites())
        .extracting(CurriculumPrerequisiteView::prerequisiteSkillCode).contains("KAFKA_BROKER");
    assertThat(view.misconceptions()).isEmpty();
    assertThat(view.relationships()).isEmpty();
    assertThat(view.prerequisiteLinks()).isEmpty();
  }

  @Test
  @DisplayName("a SUB_CONCEPT target resolves through its parent concept to the owning skill")
  void subConceptTargetResolves() {
    wire();
    MisconceptionGraphView view =
        graphFor(MisconceptionTargetType.SUB_CONCEPT, ACK_DURABILITY_SUB_CONCEPT);

    assertThat(view.target().kind()).isEqualTo(MisconceptionTargetType.SUB_CONCEPT);
    assertThat(view.target().objectiveId()).isEqualTo(ACK_DURABILITY_OBJECTIVE);
    assertThat(view.target().owningSkillCode()).isEqualTo("KAFKA_PRODUCER_ACKS");
    assertThat(view.misconceptions())
        .extracting(PublishedMisconceptionView::id)
        .contains(MISCONCEPTION_ACKS_ALL_ALONE)
        .doesNotContain(MISCONCEPTION_ACKS_1_VS_ALL);
    assertThat(view.misconceptions()).allSatisfy(mc -> {
      assertThat(mc.targetKind()).isEqualTo(MisconceptionTargetType.SUB_CONCEPT);
      assertThat(mc.targetId()).isEqualTo(ACK_DURABILITY_SUB_CONCEPT);
    });
  }

  @Test
  @DisplayName("an unknown target id fails deterministically, distinct from an empty result")
  void unknownTargetIsNotFound() {
    wire();
    assertThatThrownBy(() -> graphFor(MisconceptionTargetType.LEARNING_OBJECTIVE, UuidV7.generate()))
        .isInstanceOf(MisconceptionGraphTargetNotFoundException.class);
  }

  @Test
  @DisplayName("a kind mismatch (a SUB_CONCEPT id requested as a CONCEPT) is not found")
  void kindMismatchIsNotFound() {
    wire();
    assertThatThrownBy(() ->
        graphFor(MisconceptionTargetType.CONCEPT, ACK_DURABILITY_SUB_CONCEPT))
        .isInstanceOf(MisconceptionGraphTargetNotFoundException.class);
  }

  // -- publication filtering, enforced in SQL --------------------------------------------------

  @Test
  @DisplayName("a DRAFT misconception is hidden until it is published")
  void draftMisconceptionIsHidden() {
    wire();
    UUID draft = UuidV7.generate();
    misconceptions.insertTargetingObjective(draft, "draft mc", "fixture", ACK_DURABILITY_OBJECTIVE);

    assertThat(graphFor(MisconceptionTargetType.LEARNING_OBJECTIVE, ACK_DURABILITY_OBJECTIVE)
        .misconceptions())
        .extracting(PublishedMisconceptionView::id)
        .doesNotContain(draft);

    misconceptions.publish(draft);
    assertThat(graphFor(MisconceptionTargetType.LEARNING_OBJECTIVE, ACK_DURABILITY_OBJECTIVE)
        .misconceptions())
        .extracting(PublishedMisconceptionView::id)
        .contains(draft);
  }

  @Test
  @DisplayName("a DRAFT MISCONCEPTION_RELATED edge is hidden even when both endpoints are published")
  void draftRelationshipIsHidden() {
    wire();
    UUID m1 = freshPublishedMisconceptionOnObjective("rel-hide m1");
    UUID m2 = freshPublishedMisconceptionOnObjective("rel-hide m2");
    UUID edgeId = writeService.authorDraftRelationship(
        m1, m2, MisconceptionRelatedType.CO_OCCURS_WITH, "navigable");

    assertThat(graphFor(MisconceptionTargetType.LEARNING_OBJECTIVE, ACK_DURABILITY_OBJECTIVE)
        .relationships())
        .extracting(PublishedMisconceptionRelationshipView::id)
        .doesNotContain(edgeId);

    writeService.publishRelationship(edgeId);
    assertThat(graphFor(MisconceptionTargetType.LEARNING_OBJECTIVE, ACK_DURABILITY_OBJECTIVE)
        .relationships())
        .extracting(PublishedMisconceptionRelationshipView::id)
        .contains(edgeId);
  }

  @Test
  @DisplayName("a DRAFT MISCONCEPTION_PREREQUISITE_LINK is hidden until it is published")
  void draftPrerequisiteLinkIsHidden() {
    wire();
    UUID misconceptionId = freshPublishedMisconceptionOnObjective("link-hide mc");
    UUID linkId = writeService.authorDraftPrerequisiteLink(
        misconceptionId, KAFKA_BROKER_SKILL, "rooted in the broker prerequisite");

    assertThat(graphFor(MisconceptionTargetType.LEARNING_OBJECTIVE, ACK_DURABILITY_OBJECTIVE)
        .prerequisiteLinks())
        .extracting(PublishedMisconceptionPrerequisiteLinkView::id)
        .doesNotContain(linkId);

    writeService.publishPrerequisiteLink(linkId);
    assertThat(graphFor(MisconceptionTargetType.LEARNING_OBJECTIVE, ACK_DURABILITY_OBJECTIVE)
        .prerequisiteLinks())
        .extracting(PublishedMisconceptionPrerequisiteLinkView::id)
        .contains(linkId);
  }

  // -- external-neighbour rule: "edges among, and from, that set" -----------------------------

  @Test
  @DisplayName("a boundary edge is returned with the out-of-scope endpoint marked, and no expansion")
  void boundaryEdgeIsReturnedWithScopeFlags() {
    wire();
    // Querying the objective returns ...0f04. The seed edge ...0f10 is ...0f03 CONTRASTS_WITH
    // ...0f04, so it is a boundary edge: ...0f04 in scope, ...0f03 not.
    MisconceptionGraphView view =
        graphFor(MisconceptionTargetType.LEARNING_OBJECTIVE, ACK_DURABILITY_OBJECTIVE);

    PublishedMisconceptionRelationshipView edge = view.relationships().stream()
        .filter(candidate -> candidate.id().equals(SEED_RELATED_EDGE))
        .findFirst().orElseThrow();
    assertThat(edge.relationshipType()).isEqualTo(MisconceptionRelatedType.CONTRASTS_WITH);
    assertThat(edge.sourceMisconceptionId()).isEqualTo(MISCONCEPTION_ACKS_ALL_ALONE); // a
    assertThat(edge.targetMisconceptionId()).isEqualTo(MISCONCEPTION_ACKS_1_VS_ALL);  // b
    assertThat(edge.sourceInScope()).isFalse();
    assertThat(edge.targetInScope()).isTrue();
    assertThat(edge.symmetric()).isTrue();
    assertThat(edge.reverseReadingLabel()).isNull();

    // The out-of-scope misconception ...0f03 is not pulled into the misconception set.
    assertThat(view.misconceptions())
        .extracting(PublishedMisconceptionView::id)
        .doesNotContain(MISCONCEPTION_ACKS_ALL_ALONE);
    // ...and its own prerequisite link (...0f20) is not returned -- no one-hop expansion.
    assertThat(view.prerequisiteLinks())
        .extracting(PublishedMisconceptionPrerequisiteLinkView::id)
        .doesNotContain(SEED_PREREQUISITE_LINK);
  }

  @Test
  @DisplayName("the same seed edge, queried from the other endpoint, flips the scope flags")
  void boundaryEdgeSeenFromTheOtherSide() {
    wire();
    PublishedMisconceptionRelationshipView edge =
        graphFor(MisconceptionTargetType.SUB_CONCEPT, ACK_DURABILITY_SUB_CONCEPT).relationships()
            .stream().filter(candidate -> candidate.id().equals(SEED_RELATED_EDGE))
            .findFirst().orElseThrow();
    assertThat(edge.sourceInScope()).isTrue();   // ...0f03 targets the sub-concept
    assertThat(edge.targetInScope()).isFalse();  // ...0f04 targets the objective
  }

  // -- symmetry / direction rendering -------------------------------------------------------------

  @Test
  @DisplayName("a symmetric CONTRASTS_WITH / CO_OCCURS_WITH edge is returned exactly once")
  void symmetricEdgeReturnedOnce() {
    wire();
    // Seed CONTRASTS_WITH.
    assertThat(graphFor(MisconceptionTargetType.LEARNING_OBJECTIVE, ACK_DURABILITY_OBJECTIVE)
        .relationships())
        .filteredOn(edge -> edge.id().equals(SEED_RELATED_EDGE))
        .hasSize(1);

    UUID m1 = freshPublishedMisconceptionOnObjective("sym once m1");
    UUID m2 = freshPublishedMisconceptionOnObjective("sym once m2");
    UUID coOccurs = writeService.authorDraftRelationship(
        m2, m1, MisconceptionRelatedType.CO_OCCURS_WITH, "authored reversed on purpose");
    writeService.publishRelationship(coOccurs);

    List<PublishedMisconceptionRelationshipView> forCoOccurs =
        graphFor(MisconceptionTargetType.LEARNING_OBJECTIVE, ACK_DURABILITY_OBJECTIVE)
            .relationships().stream().filter(edge -> edge.id().equals(coOccurs)).toList();
    assertThat(forCoOccurs).hasSize(1);
    // Stored under canonical ordering, not as authored (m2, m1).
    assertThat(forCoOccurs.get(0).sourceMisconceptionId().toString())
        .isLessThan(forCoOccurs.get(0).targetMisconceptionId().toString());
  }

  @Test
  @DisplayName("SPECIALISES keeps its source -> target and reads back as GENERALISES; no second row")
  void specialisesRendersWithReverseLabel() {
    wire();
    UUID specific = freshPublishedMisconceptionOnObjective("spec specific");
    UUID general = freshPublishedMisconceptionOnObjective("spec general");
    UUID edgeId = writeService.authorDraftRelationship(
        specific, general, MisconceptionRelatedType.SPECIALISES, "specific is a kind of general");
    writeService.publishRelationship(edgeId);

    List<PublishedMisconceptionRelationshipView> edges =
        graphFor(MisconceptionTargetType.LEARNING_OBJECTIVE, ACK_DURABILITY_OBJECTIVE)
            .relationships().stream().filter(edge -> edge.id().equals(edgeId)).toList();
    assertThat(edges).hasSize(1);
    PublishedMisconceptionRelationshipView edge = edges.get(0);
    assertThat(edge.relationshipType()).isEqualTo(MisconceptionRelatedType.SPECIALISES);
    assertThat(edge.sourceMisconceptionId()).isEqualTo(specific);
    assertThat(edge.targetMisconceptionId()).isEqualTo(general);
    assertThat(edge.symmetric()).isFalse();
    assertThat(edge.reverseReadingLabel()).isEqualTo("GENERALISES");

    Integer generalisesRows = runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.misconception_relationship WHERE relationship_type = 'GENERALISES'",
        Integer.class);
    assertThat(generalisesRows).isZero();
  }

  // -- bounded query count / no N+1 -------------------------------------------------------------

  @Test
  @DisplayName("the repository query count is O(1) in the number of misconceptions")
  void queryCountIsConstant() {
    wire();
    countingJdbc.reset();
    countingQueryService.graphFor(
        new MisconceptionGraphTarget(MisconceptionTargetType.SUB_CONCEPT, ACK_DURABILITY_SUB_CONCEPT));
    int beforeMoreMisconceptions = countingJdbc.count();

    for (int i = 0; i < 6; i++) {
      freshPublishedMisconceptionOnSubConcept("count fixture " + i);
    }

    countingJdbc.reset();
    countingQueryService.graphFor(
        new MisconceptionGraphTarget(MisconceptionTargetType.SUB_CONCEPT, ACK_DURABILITY_SUB_CONCEPT));
    int afterSixMoreMisconceptions = countingJdbc.count();

    // Five statements when the node has >= 1 published misconception (Q1..Q5), and adding six more
    // misconceptions changes nothing -- the relationship / prerequisite-link reads are bulk IN(...),
    // never one query per misconception.
    assertThat(beforeMoreMisconceptions).isEqualTo(5);
    assertThat(afterSixMoreMisconceptions).isEqualTo(5);
  }

  // -- determinism ----------------------------------------------------------------------------------

  @Test
  @DisplayName("the same DB state produces an equal projection, in the same order, every time")
  void projectionIsDeterministic() {
    wire();
    UUID m1 = freshPublishedMisconceptionOnObjective("det m1");
    UUID m2 = freshPublishedMisconceptionOnObjective("det m2");
    UUID m3 = freshPublishedMisconceptionOnObjective("det m3");
    writeService.publishRelationship(writeService.authorDraftRelationship(
        m1, m2, MisconceptionRelatedType.CO_OCCURS_WITH, "a"));
    writeService.publishRelationship(writeService.authorDraftRelationship(
        m2, m3, MisconceptionRelatedType.CONTRASTS_WITH, "b"));
    writeService.publishPrerequisiteLink(writeService.authorDraftPrerequisiteLink(
        m1, KAFKA_BROKER_SKILL, "c"));

    MisconceptionGraphView first =
        graphFor(MisconceptionTargetType.LEARNING_OBJECTIVE, ACK_DURABILITY_OBJECTIVE);
    MisconceptionGraphView second =
        graphFor(MisconceptionTargetType.LEARNING_OBJECTIVE, ACK_DURABILITY_OBJECTIVE);

    assertThat(first).isEqualTo(second);
    assertThat(first.misconceptions()).isEqualTo(second.misconceptions());
    assertThat(first.relationships()).isEqualTo(second.relationships());
    assertThat(first.prerequisiteLinks()).isEqualTo(second.prerequisiteLinks());
  }

  // -- read-only -----------------------------------------------------------------------------------

  @Test
  @DisplayName("a query mutates no graph row")
  void queryMutatesNothing() {
    wire();
    long relBefore = countRows("core.misconception_relationship");
    long linkBefore = countRows("core.misconception_prerequisite_link");
    long mcBefore = countRows("core.misconception");

    graphFor(MisconceptionTargetType.LEARNING_OBJECTIVE, ACK_DURABILITY_OBJECTIVE);
    graphFor(MisconceptionTargetType.SUB_CONCEPT, ACK_DURABILITY_SUB_CONCEPT);
    graphFor(MisconceptionTargetType.CONCEPT, ACK_DURABILITY_CONCEPT);

    assertThat(countRows("core.misconception_relationship")).isEqualTo(relBefore);
    assertThat(countRows("core.misconception_prerequisite_link")).isEqualTo(linkBefore);
    assertThat(countRows("core.misconception")).isEqualTo(mcBefore);
  }

  // The projection type itself is proven learner-free by MisconceptionGraphProjectionTests
  // (reflection over every record component). It is not re-asserted here by scanning toString():
  // an authored rationale is prose and can legitimately contain the word "learner".

  // -- helpers -----------------------------------------------------------------------------------

  private long countRows(String table) {
    return runtimeJdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  /** A JdbcTemplate that counts every {@code query(sql, rowMapper, args...)} call the repository
   * makes -- the mechanism behind the O(1) query-count regression test. */
  private static final class CountingJdbcTemplate extends JdbcTemplate {
    private final AtomicInteger queries = new AtomicInteger();

    private CountingJdbcTemplate(javax.sql.DataSource dataSource) {
      super(dataSource);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      queries.incrementAndGet();
      return super.query(sql, rowMapper, args);
    }

    private void reset() {
      queries.set(0);
    }

    private int count() {
      return queries.get();
    }
  }

  private static String currentDatabase(Statement statement) throws SQLException {
    try (ResultSet result = statement.executeQuery("SELECT current_database()")) {
      result.next();
      return result.getString(1);
    }
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing required environment variable: " + name);
    }
    return value;
  }
}
