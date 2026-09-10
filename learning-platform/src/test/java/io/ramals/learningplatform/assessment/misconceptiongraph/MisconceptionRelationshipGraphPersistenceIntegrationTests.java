package io.ramals.learningplatform.assessment.misconceptiongraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.MisconceptionRepository;
import io.ramals.learningplatform.observability.UuidV7;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * M2-ADR-033 step 1 against real PostgreSQL and the real, already-seeded KAFKA v2 acks curriculum
 * -- the same foundation-stage pattern {@code GranularDiagnosticOntologyPersistenceIntegrationTests}
 * established. Persistence, the DRAFT/PUBLISHED lifecycle, the database invariants, and the
 * deterministic reason codes; no runtime selector is exercised because none reads the graph.
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_POSTGRES_ALLOW_RESET", matches = "(?i)true")
class MisconceptionRelationshipGraphPersistenceIntegrationTests {

  private static final String MIGRATION_USER = "ramals_core_migration";
  private static final String MIGRATION_PASSWORD = "m0-t05-migration-test";
  private static final String RUNTIME_USER = "ramals_core_runtime";
  private static final String RUNTIME_PASSWORD = "m0-t05-runtime-test";

  // Real, already-seeded content.
  private static final UUID ACK_DURABILITY_OBJECTIVE =
      UUID.fromString("01900000-0000-7000-8000-000000000d11");
  private static final UUID MISCONCEPTION_ACKS_ALL_ALONE =
      UUID.fromString("01900000-0000-7000-8000-000000000f03");
  private static final UUID MISCONCEPTION_ACKS_1_VS_ALL =
      UUID.fromString("01900000-0000-7000-8000-000000000f04");
  private static final UUID SEED_RELATED_EDGE =
      UUID.fromString("01900000-0000-7000-8000-000000000f10");
  private static final UUID SEED_PREREQUISITE_LINK =
      UUID.fromString("01900000-0000-7000-8000-000000000f20");
  private static final UUID KAFKA_BROKER_SKILL =
      UUID.fromString("01900000-0000-7000-8000-000000000101"); // a real prerequisite of ...0107
  private static final UUID KAFKA_PRODUCER_IDEMPOTENCE_SKILL =
      UUID.fromString("01900000-0000-7000-8000-000000000108"); // NOT a prerequisite of ...0107

  private static String databaseUrl;
  private JdbcTemplate runtimeJdbc;
  private MisconceptionRepository misconceptions;
  private MisconceptionRelationshipRepository repository;
  private MisconceptionRelationshipService service;

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
    if (service == null) {
      runtimeJdbc = new JdbcTemplate(
          new DriverManagerDataSource(databaseUrl, RUNTIME_USER, RUNTIME_PASSWORD));
      misconceptions = new MisconceptionRepository(runtimeJdbc);
      repository = new MisconceptionRelationshipRepository(runtimeJdbc);
      service = new MisconceptionRelationshipService(
          repository, new MisconceptionRelationshipValidator(repository));
    }
  }

  /** A fresh DRAFT misconception targeting the real ACK_DURABILITY objective. */
  private UUID freshDraftMisconception(String name) {
    UUID id = UuidV7.generate();
    misconceptions.insertTargetingObjective(id, name, "V061 graph fixture", ACK_DURABILITY_OBJECTIVE);
    return id;
  }

  private UUID freshPublishedMisconception(String name) {
    UUID id = freshDraftMisconception(name);
    misconceptions.publish(id);
    return id;
  }

  private String relationshipStatus(UUID id) {
    return runtimeJdbc.queryForObject(
        "SELECT status FROM core.misconception_relationship WHERE id = ?", String.class, id);
  }

  private long relationshipCount() {
    return runtimeJdbc.queryForObject(
        "SELECT count(*) FROM core.misconception_relationship", Long.class);
  }

  // -- the grounded seed --------------------------------------------------------------------------

  @Test
  @DisplayName("the V061 seed edge and prerequisite link are published and readable")
  void theSeedIsPublishedAndReadable() {
    wire();
    MisconceptionRelationship edge = repository.findRelationshipById(SEED_RELATED_EDGE).orElseThrow();
    assertThat(edge.status()).isEqualTo(MisconceptionGraphStatus.PUBLISHED);
    assertThat(edge.relatedType()).isEqualTo(MisconceptionRelatedType.CONTRASTS_WITH);
    assertThat(edge.misconceptionAId()).isEqualTo(MISCONCEPTION_ACKS_ALL_ALONE);
    assertThat(edge.misconceptionBId()).isEqualTo(MISCONCEPTION_ACKS_1_VS_ALL);
    assertThat(edge.rationale()).isNotBlank();

    MisconceptionPrerequisiteLink link =
        repository.findPrerequisiteLinkById(SEED_PREREQUISITE_LINK).orElseThrow();
    assertThat(link.status()).isEqualTo(MisconceptionGraphStatus.PUBLISHED);
    assertThat(link.misconceptionId()).isEqualTo(MISCONCEPTION_ACKS_ALL_ALONE);
    assertThat(link.prerequisiteSkillId()).isEqualTo(KAFKA_BROKER_SKILL);
  }

  // -- persistence: valid saves -----------------------------------------------------------------

  @Test
  void aValidRelatedEdgeSavesAsDraftThenPublishes() {
    wire();
    UUID m1 = freshPublishedMisconception("rel-save m1");
    UUID m2 = freshPublishedMisconception("rel-save m2");

    UUID edgeId = service.authorDraftRelationship(
        m1, m2, MisconceptionRelatedType.CO_OCCURS_WITH, "meaningfully related for navigation");
    assertThat(repository.findRelationshipById(edgeId).orElseThrow().status())
        .isEqualTo(MisconceptionGraphStatus.DRAFT);

    service.publishRelationship(edgeId);
    assertThat(relationshipStatus(edgeId)).isEqualTo("PUBLISHED");
  }

  @Test
  void aValidPrerequisiteLinkSavesThenPublishes() {
    wire();
    UUID misconceptionId = freshPublishedMisconception("prereq-save misconception");

    UUID linkId = service.authorDraftPrerequisiteLink(
        misconceptionId, KAFKA_BROKER_SKILL, "commonly rooted in an unsecured broker prerequisite");
    assertThat(repository.findPrerequisiteLinkById(linkId).orElseThrow().status())
        .isEqualTo(MisconceptionGraphStatus.DRAFT);

    service.publishPrerequisiteLink(linkId);
    assertThat(repository.findPrerequisiteLinkById(linkId).orElseThrow().isPublished()).isTrue();
  }

  // -- referential integrity / self / duplicate ------------------------------------------------

  @Test
  void anUnknownEndpointIsRejectedByTheValidatorAndTheForeignKey() {
    wire();
    UUID known = freshPublishedMisconception("fk known");
    UUID unknown = UuidV7.generate();

    assertThatThrownBy(() -> service.authorDraftRelationship(
        known, unknown, MisconceptionRelatedType.CO_OCCURS_WITH, "why"))
        .isInstanceOf(MisconceptionGraphValidationException.class)
        .extracting(e -> ((MisconceptionGraphValidationException) e).reasonCode())
        .isEqualTo(MisconceptionRelationshipReasonCode.TARGET_MISCONCEPTION_NOT_FOUND);

    // The database FK is the backstop for a direct write that bypasses the service.
    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.misconception_relationship
          (id, misconception_a_id, misconception_b_id, relationship_type, status, rationale)
        VALUES (?, ?, ?, 'CO_OCCURS_WITH', 'DRAFT', 'x')
        """, UuidV7.generate(), known, unknown))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aSelfEdgeIsRejectedByTheValidatorAndTheCheckConstraint() {
    wire();
    UUID m = freshPublishedMisconception("self edge");

    assertThatThrownBy(() -> service.authorDraftRelationship(
        m, m, MisconceptionRelatedType.CO_OCCURS_WITH, "why"))
        .isInstanceOf(MisconceptionGraphValidationException.class)
        .extracting(e -> ((MisconceptionGraphValidationException) e).reasonCode())
        .isEqualTo(MisconceptionRelationshipReasonCode.SELF_RELATIONSHIP_NOT_ALLOWED);

    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.misconception_relationship
          (id, misconception_a_id, misconception_b_id, relationship_type, status, rationale)
        VALUES (?, ?, ?, 'CO_OCCURS_WITH', 'DRAFT', 'x')
        """, UuidV7.generate(), m, m))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  @DisplayName("a symmetric RELATED edge and its reverse are one logical edge -- authored either way")
  void symmetricRelatedEdgeIsStoredOnceUnderCanonicalOrdering() {
    wire();
    UUID m1 = freshPublishedMisconception("dup m1");
    UUID m2 = freshPublishedMisconception("dup m2");

    service.authorDraftRelationship(m1, m2, MisconceptionRelatedType.CO_OCCURS_WITH, "first");

    assertThatThrownBy(() -> service.authorDraftRelationship(
        m2, m1, MisconceptionRelatedType.CO_OCCURS_WITH, "reversed"))
        .isInstanceOf(MisconceptionGraphValidationException.class)
        .extracting(e -> ((MisconceptionGraphValidationException) e).reasonCode())
        .isEqualTo(MisconceptionRelationshipReasonCode.DUPLICATE_RELATIONSHIP);
  }

  @Test
  @DisplayName("the canonical-order check also refuses a raw un-canonical symmetric insert")
  void aRawUncanonicalSymmetricInsertIsRefusedByTheDatabase() {
    wire();
    UUID low = freshPublishedMisconception("canon low");
    UUID high = freshPublishedMisconception("canon high");
    UUID a = low.toString().compareTo(high.toString()) < 0 ? low : high;
    UUID b = a == low ? high : low;

    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.misconception_relationship
          (id, misconception_a_id, misconception_b_id, relationship_type, status, rationale)
        VALUES (?, ?, ?, 'CO_OCCURS_WITH', 'DRAFT', 'x')
        """, UuidV7.generate(), b, a)) // deliberately b, a -- out of canonical order
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  @DisplayName("two different typed relationships between the same pair are both allowed")
  void twoDifferentTypesOnTheSamePairAreAllowed() {
    wire();
    UUID m1 = freshPublishedMisconception("types m1");
    UUID m2 = freshPublishedMisconception("types m2");

    assertThatCode(() -> {
      service.authorDraftRelationship(m1, m2, MisconceptionRelatedType.CO_OCCURS_WITH, "co-occurs");
      service.authorDraftRelationship(m1, m2, MisconceptionRelatedType.CONTRASTS_WITH, "contrasts");
    }).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a concurrent identical insert cannot produce two logical edges")
  void aRawDuplicateInsertCannotProduceTwoLogicalEdges() {
    wire();
    UUID m1 = freshPublishedMisconception("race m1");
    UUID m2 = freshPublishedMisconception("race m2");
    CanonicalMisconceptionPair pair =
        CanonicalMisconceptionPair.of(MisconceptionRelatedType.CO_OCCURS_WITH, m1, m2);

    long before = relationshipCount();
    repository.insertDraftRelationship(pair, MisconceptionRelatedType.CO_OCCURS_WITH, "one");
    assertThatThrownBy(() ->
        repository.insertDraftRelationship(pair, MisconceptionRelatedType.CO_OCCURS_WITH, "two"))
        .isInstanceOf(DataAccessException.class);
    assertThat(relationshipCount()).isEqualTo(before + 1);
  }

  // -- direction & the SPECIALISES DAG --------------------------------------------------------

  @Test
  @DisplayName("SPECIALISES preserves its authored a -> b direction")
  void specialisesPreservesDirection() {
    wire();
    UUID specific = freshPublishedMisconception("spec specific");
    UUID general = freshPublishedMisconception("spec general");

    UUID edgeId = service.authorDraftRelationship(
        specific, general, MisconceptionRelatedType.SPECIALISES, "specific is a kind of general");
    MisconceptionRelationship edge = repository.findRelationshipById(edgeId).orElseThrow();
    assertThat(edge.misconceptionAId()).isEqualTo(specific);
    assertThat(edge.misconceptionBId()).isEqualTo(general);
  }

  @Test
  @DisplayName("a direct SPECIALISES cycle (A->B then B->A) is rejected")
  void directSpecialisesCycleIsRejected() {
    wire();
    UUID a = freshPublishedMisconception("cycle a");
    UUID b = freshPublishedMisconception("cycle b");
    service.authorDraftRelationship(a, b, MisconceptionRelatedType.SPECIALISES, "a is a kind of b");

    assertThatThrownBy(() -> service.authorDraftRelationship(
        b, a, MisconceptionRelatedType.SPECIALISES, "b is a kind of a"))
        .isInstanceOf(MisconceptionGraphValidationException.class)
        .extracting(e -> ((MisconceptionGraphValidationException) e).reasonCode())
        .isEqualTo(MisconceptionRelationshipReasonCode.SPECIALISES_CYCLE_NOT_ALLOWED);
  }

  @Test
  @DisplayName("a transitive SPECIALISES cycle (A->B->C then C->A) is rejected")
  void transitiveSpecialisesCycleIsRejected() {
    wire();
    UUID a = freshPublishedMisconception("trans a");
    UUID b = freshPublishedMisconception("trans b");
    UUID c = freshPublishedMisconception("trans c");
    service.authorDraftRelationship(a, b, MisconceptionRelatedType.SPECIALISES, "a->b");
    service.authorDraftRelationship(b, c, MisconceptionRelatedType.SPECIALISES, "b->c");

    assertThatThrownBy(() -> service.authorDraftRelationship(
        c, a, MisconceptionRelatedType.SPECIALISES, "c->a"))
        .isInstanceOf(MisconceptionGraphValidationException.class)
        .extracting(e -> ((MisconceptionGraphValidationException) e).reasonCode())
        .isEqualTo(MisconceptionRelationshipReasonCode.SPECIALISES_CYCLE_NOT_ALLOWED);
  }

  @Test
  @DisplayName("an acyclic SPECIALISES chain, including a shortcut edge, is accepted")
  void anAcyclicSpecialisesChainIsAccepted() {
    wire();
    UUID a = freshPublishedMisconception("dag a");
    UUID b = freshPublishedMisconception("dag b");
    UUID c = freshPublishedMisconception("dag c");

    assertThatCode(() -> {
      service.authorDraftRelationship(a, b, MisconceptionRelatedType.SPECIALISES, "a->b");
      service.authorDraftRelationship(b, c, MisconceptionRelatedType.SPECIALISES, "b->c");
      service.authorDraftRelationship(a, c, MisconceptionRelatedType.SPECIALISES, "a->c shortcut");
    }).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("the SPECIALISES DAG check also fires on a raw database insert")
  void rawSpecialisesCycleInsertIsRejectedByTheTrigger() {
    wire();
    UUID a = freshPublishedMisconception("raw cycle a");
    UUID b = freshPublishedMisconception("raw cycle b");
    service.authorDraftRelationship(a, b, MisconceptionRelatedType.SPECIALISES, "a->b");

    assertThatThrownBy(() -> runtimeJdbc.update("""
        INSERT INTO core.misconception_relationship
          (id, misconception_a_id, misconception_b_id, relationship_type, status, rationale)
        VALUES (?, ?, ?, 'SPECIALISES', 'DRAFT', 'raw b->a')
        """, UuidV7.generate(), b, a))
        .isInstanceOf(DataAccessException.class);
  }

  // -- publication lifecycle ------------------------------------------------------------------

  @Test
  void aPublishedRelatedEdgeIsImmutable() {
    wire();
    UUID m1 = freshPublishedMisconception("immutable m1");
    UUID m2 = freshPublishedMisconception("immutable m2");
    UUID edgeId = service.authorDraftRelationship(
        m1, m2, MisconceptionRelatedType.CO_OCCURS_WITH, "why");
    service.publishRelationship(edgeId);

    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE core.misconception_relationship SET rationale = 'edited' WHERE id = ?", edgeId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> runtimeJdbc.update(
        "DELETE FROM core.misconception_relationship WHERE id = ?", edgeId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aRelatedEdgeCannotBePublishedWhileAnEndpointIsStillDraft() {
    wire();
    UUID published = freshPublishedMisconception("pub endpoint");
    UUID draft = freshDraftMisconception("draft endpoint");

    // A DRAFT edge to a DRAFT endpoint is fine -- authoring the two together is normal.
    UUID edgeId = service.authorDraftRelationship(
        published, draft, MisconceptionRelatedType.CO_OCCURS_WITH, "why");

    assertThatThrownBy(() -> service.publishRelationship(edgeId))
        .isInstanceOf(MisconceptionGraphValidationException.class)
        .extracting(e -> ((MisconceptionGraphValidationException) e).reasonCode())
        .isEqualTo(MisconceptionRelationshipReasonCode.PUBLISHED_ENDPOINT_REQUIRED);
  }

  @Test
  void publishingARelationshipIsIdempotent() {
    wire();
    UUID m1 = freshPublishedMisconception("idem m1");
    UUID m2 = freshPublishedMisconception("idem m2");
    UUID edgeId = service.authorDraftRelationship(
        m1, m2, MisconceptionRelatedType.CO_OCCURS_WITH, "why");
    service.publishRelationship(edgeId);

    assertThatCode(() -> service.publishRelationship(edgeId)).doesNotThrowAnyException();
    assertThat(relationshipStatus(edgeId)).isEqualTo("PUBLISHED");
  }

  // -- prerequisite link: the curriculum-prerequisite rule ------------------------------------

  @Test
  void aPrerequisiteLinkToASkillThatIsNotACurriculumPrerequisiteIsRejectedAtPublish() {
    wire();
    UUID misconceptionId = freshPublishedMisconception("bad prereq");

    // KAFKA_PRODUCER_IDEMPOTENCE (...0108) has ...0107 as ITS prerequisite, not the reverse, so it
    // is not a prerequisite of the ACK_DURABILITY misconception's owning skill.
    UUID linkId = service.authorDraftPrerequisiteLink(
        misconceptionId, KAFKA_PRODUCER_IDEMPOTENCE_SKILL, "authored ahead of validation");

    assertThatThrownBy(() -> service.publishPrerequisiteLink(linkId))
        .isInstanceOf(MisconceptionGraphValidationException.class)
        .extracting(e -> ((MisconceptionGraphValidationException) e).reasonCode())
        .isEqualTo(
            MisconceptionRelationshipReasonCode.PREREQUISITE_LINK_NOT_A_CURRICULUM_PREREQUISITE);

    // The database trigger is the backstop for a raw publish.
    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE core.misconception_prerequisite_link SET status = 'PUBLISHED' WHERE id = ?", linkId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aPrerequisiteLinkCannotBePublishedWhileTheMisconceptionIsStillDraft() {
    wire();
    UUID draftMisconception = freshDraftMisconception("draft for prereq");
    UUID linkId = service.authorDraftPrerequisiteLink(
        draftMisconception, KAFKA_BROKER_SKILL, "why");

    assertThatThrownBy(() -> service.publishPrerequisiteLink(linkId))
        .isInstanceOf(MisconceptionGraphValidationException.class)
        .extracting(e -> ((MisconceptionGraphValidationException) e).reasonCode())
        .isEqualTo(MisconceptionRelationshipReasonCode.PUBLISHED_ENDPOINT_REQUIRED);
  }

  @Test
  void aPublishedPrerequisiteLinkIsImmutable() {
    wire();
    UUID misconceptionId = freshPublishedMisconception("immutable prereq");
    UUID linkId = service.authorDraftPrerequisiteLink(misconceptionId, KAFKA_BROKER_SKILL, "why");
    service.publishPrerequisiteLink(linkId);

    assertThatThrownBy(() -> runtimeJdbc.update(
        "UPDATE core.misconception_prerequisite_link SET rationale = 'x' WHERE id = ?", linkId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  @DisplayName("a prerequisite link resolves the owning skill through a SUB_CONCEPT target arc too")
  void prerequisiteLinkResolvesOwningSkillThroughASubConceptTargetArc() {
    wire();
    // A fresh published misconception targeting SUB_CONCEPT ...0f02 -> CONCEPT ...0f01 -> objective
    // ...0d11 -> skill ...0107. KAFKA_BROKER (...0101) is a real prerequisite of ...0107 for
    // curriculum version ...0004, so the owning-skill resolution must walk sub-concept -> parent.
    UUID subConceptTargeted = UuidV7.generate();
    misconceptions.insertTargetingNode(
        subConceptTargeted, "sub-concept-arc fixture", "V061 graph fixture",
        UUID.fromString("01900000-0000-7000-8000-000000000f02"));
    misconceptions.publish(subConceptTargeted);

    UUID linkId = service.authorDraftPrerequisiteLink(
        subConceptTargeted, KAFKA_BROKER_SKILL, "sub-concept arc resolution");
    assertThatCode(() -> service.publishPrerequisiteLink(linkId)).doesNotThrowAnyException();
  }

  // -- helpers -------------------------------------------------------------------------------------

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
