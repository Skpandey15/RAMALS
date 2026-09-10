package io.ramals.learningplatform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M2-ADR-032 step 3, requirement 18: the bounded diagnostic-probe reasoning path -- the Java call
 * site that solicits and routes an advisory recommendation -- can reach neither the authoritative
 * learner-state writers nor {@code DIAGNOSTIC_SELECTION_V1-V5}.
 *
 * <p>Complements {@code DiagnosticProbeProposalServiceTests.cannotReachAuthoritativeOrSelectionState}
 * (PR #266), which covers the gate/service and the {@code DiagnosticProbeProposal*} classes; this
 * one covers the step-3 additions -- {@link
 * io.ramals.learningplatform.diagnosticassessment.DiagnosticProbeRecommendationOrchestrator}, {@link
 * io.ramals.learningplatform.diagnosticassessment.DiagnosticProbeContextAssembler} and the AI-plane
 * client {@code RamalsAiDiagnosticProbeClient} -- so the boundary is proven for the whole path, not
 * just the seam it plugs into.
 *
 * <p>The context assembler <em>does</em> depend on {@code DiagnosticReportService} and {@code
 * LongitudinalEvidenceService}: those are {@code @Transactional(readOnly = true)} H6/H7 read
 * services (M2-ADR-029/030), the same ones the MCP-2 tools already expose. The rule forbids the
 * writers and the selectors by fully-qualified name, never the read services.
 */
@Tag("architecture")
class DiagnosticProbeReasoningArchitectureGuardrailTests {

  private static final String BASE = "io.ramals.learningplatform";

  private final JavaClasses reasoningPath =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE + ".diagnosticassessment", BASE + ".ai");

  @Test
  void reasoningPathCannotReachAuthoritativeWritersOrSelection() {
    noClasses()
        .that()
        .haveSimpleNameStartingWith("DiagnosticProbeRecommendation")
        .or()
        .haveSimpleNameStartingWith("DiagnosticProbeContextAssembler")
        .or()
        .haveSimpleName("RamalsAiDiagnosticProbeClient")
        .or()
        .haveSimpleName("DiagnosticProbePort")
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
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticSubmissionService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.AssessmentRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.AdaptiveDiagnosticSelector")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(
            BASE + ".assessment.HypothesisDrivenProbeDiagnosticSelector")
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(JdbcTemplate.class)
        .because(
            "M2-ADR-032: the advisory diagnostic-probe reasoning path never writes learner state "
                + "and never reaches DIAGNOSTIC_SELECTION_V1-V5 -- eligibility and execution stay "
                + "deterministic, and a recommendation only ever reaches the fail-closed gate")
        .check(reasoningPath);
  }
}
