package io.ramals.learningplatform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M2-ADR-033 step 1 and step 2 boundaries, executable.
 *
 * <ul>
 *   <li>The misconception graph is authored curriculum knowledge, not learner state and not AI
 *       state: its classes reach no mastery / evidence / progression / diagnostic-confidence
 *       writer (§4), and nothing in the AI plane, orchestration, or MCP can author or mutate it
 *       (§14/§17).
 *   <li>It has no diagnostic runtime integration: the frozen {@code DIAGNOSTIC_SELECTION_V1}-
 *       {@code V5} selectors, {@code DiagnosticService}, {@code DiagnosticSubmissionService}, and
 *       {@code ProbeRelationshipResolver} do not depend on the graph package -- the deliberate
 *       "X" between the graph and selection (§6/§15).
 *   <li>Step 2's read-only query surface reads no learner-scoped facts (§5, prompt §15/§16) and
 *       writes no graph rows (prompt §31.4): the {@code *Query*} classes touch no {@code
 *       LearnerService} / H6 / H7 / evidence / confidence code and call no {@code JdbcTemplate}
 *       mutation method.
 * </ul>
 */
@Tag("architecture")
class MisconceptionGraphArchitectureGuardrailTests {

  private static final String BASE = "io.ramals.learningplatform";
  private static final String GRAPH_PACKAGE = BASE + ".assessment.misconceptiongraph";

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE);

  @Test
  @DisplayName("§4 -- the misconception graph writes no learner state and no confidence")
  void graphCannotReachLearnerStateOrConfidenceWriters() {
    noClasses()
        .that()
        .resideInAPackage(GRAPH_PACKAGE)
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".mastery.MasteryRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".mastery.MasteryService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".evidence.EvidenceRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".evidence.EvidenceService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".learning.ProgressionRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".learning.ProgressionService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.MisconceptionEvidenceCaptureService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.MisconceptionConfidenceRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.MisconceptionConfidenceService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticConfidenceRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticConfidenceService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticSubmissionService")
        .because(
            "M2-ADR-033 §4: an edge is authored knowledge about misconceptions in the abstract -- "
                + "it never carries or derives a learner-scoped probability or confidence, and "
                + "(learner_id, misconception_id) confidence stays exactly G3")
        .check(classes);
  }

  @Test
  @DisplayName("§6/§15 -- the graph does not reach DIAGNOSTIC_SELECTION or probe resolution")
  void graphCannotReachDiagnosticSelectionOrProbeResolution() {
    noClasses()
        .that()
        .resideInAPackage(GRAPH_PACKAGE)
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticFormSelector")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.AdaptiveDiagnosticSelector")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.PrerequisiteAwareDiagnosticSelector")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.HypothesisConfirmationDiagnosticSelector")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.HypothesisDrivenProbeDiagnosticSelector")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.ProbeRelationshipResolver")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.ProbeRelationshipService")
        .because(
            "M2-ADR-033 §6/§15: step 1 is representation only -- no selection engine consumes the "
                + "graph, and the graph consumes no selector")
        .check(classes);
  }

  @Test
  @DisplayName("§6/§15 -- DIAGNOSTIC_SELECTION and DiagnosticService do not depend on the graph")
  void diagnosticSelectionDoesNotDependOnTheGraph() {
    noClasses()
        .that()
        .haveSimpleName("DiagnosticService")
        .or()
        .haveSimpleName("DiagnosticSubmissionService")
        .or()
        .haveSimpleName("DiagnosticFormSelector")
        .or()
        .haveSimpleName("AdaptiveDiagnosticSelector")
        .or()
        .haveSimpleName("PrerequisiteAwareDiagnosticSelector")
        .or()
        .haveSimpleName("HypothesisConfirmationDiagnosticSelector")
        .or()
        .haveSimpleName("HypothesisDrivenProbeDiagnosticSelector")
        .or()
        .haveSimpleName("ProbeRelationshipResolver")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(GRAPH_PACKAGE)
        .because(
            "M2-ADR-033 §6/§15: the deliberate 'X' -- no selection engine reads the misconception "
                + "relationship graph in this milestone")
        .check(classes);
  }

  @Test
  @DisplayName("§14/§17 -- the AI plane, orchestration, and MCP cannot author the graph")
  void aiPlaneCannotAuthorMisconceptionRelationships() {
    noClasses()
        .that()
        .resideInAnyPackage(BASE + ".ai..", BASE + ".orchestration..", BASE + ".mcp..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(GRAPH_PACKAGE)
        .because(
            "M2-ADR-033 §14/§17: only deterministic Java services author relationship edges; an "
                + "LLM / LangGraph / MCP / AIProposalEnvelope / DiagnosticProbeProposal never "
                + "writes or mutates one")
        .check(classes);
  }

  // -- step 2: the bounded read-only query surface --------------------------------------------

  @Test
  @DisplayName("step 2 -- the query surface composes no learner state and no H6/H7/G2/G3")
  void querySurfaceComposesNoLearnerState() {
    noClasses()
        .that()
        .resideInAPackage(GRAPH_PACKAGE)
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".learner.LearnerService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".learner.LearnerRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticReportService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticReportRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.LongitudinalEvidenceService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.MisconceptionEvidenceObservationRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticConfidenceCalculatorV1")
        .because(
            "M2-ADR-033 §5, prompt §15/§16: the Step-2 surface exposes authored knowledge only -- "
                + "it takes no learner id and composes no evidence / mastery / G2 / G3 / H6 / H7")
        .check(classes);
  }

  @Test
  @DisplayName("step 2 -- the query classes never call a JdbcTemplate mutation method")
  void queryClassesNeverMutate() {
    DescribedPredicate<JavaCall<?>> aJdbcMutation = new DescribedPredicate<>(
        "a JdbcTemplate write (update / execute / batchUpdate)") {
      @Override
      public boolean test(JavaCall<?> call) {
        String owner = call.getTargetOwner().getFullName();
        String name = call.getName();
        return owner.equals("org.springframework.jdbc.core.JdbcTemplate")
            && (name.equals("update") || name.equals("execute") || name.equals("batchUpdate"));
      }
    };
    noClasses()
        .that()
        .resideInAPackage(GRAPH_PACKAGE)
        .and()
        .haveSimpleNameStartingWith("MisconceptionGraphQuery")
        .should()
        .callMethodWhere(aJdbcMutation)
        .because(
            "M2-ADR-033 §5, prompt §21/§31.4: the Step-2 query surface is SELECT-only -- it "
                + "authors and publishes nothing, and the write path stays "
                + "MisconceptionRelationshipService")
        .check(classes);
  }

  @Test
  @DisplayName("step 2 -- the read query surface does not depend on the write service")
  void queryServiceDoesNotDependOnTheWriteService() {
    noClasses()
        .that()
        .haveSimpleName("MisconceptionGraphQueryService")
        .or()
        .haveSimpleName("MisconceptionGraphQueryRepository")
        .should()
        .dependOnClassesThat()
        .haveSimpleName("MisconceptionRelationshipService")
        .orShould()
        .dependOnClassesThat()
        .haveSimpleName("MisconceptionRelationshipRepository")
        .orShould()
        .dependOnClassesThat()
        .haveSimpleName("MisconceptionRelationshipValidator")
        .because(
            "prompt §21/§31.4: reading authored knowledge shares no code with authoring it; the "
                + "query surface reads the tables directly")
        .check(classes);
  }
}
