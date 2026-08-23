package io.ramals.learningplatform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Executable boundaries for the modular monolith. These rules inspect compiled dependencies. */
@AnalyzeClasses(packages = "io.ramals.learningplatform", importOptions = DoNotIncludeTests.class)
@Tag("architecture")
class ArchitectureGuardrailTests {

  private static final String BASE = "io.ramals.learningplatform";
  private static final String[] GENERIC_PACKAGES = {
    BASE + ".curriculum..", BASE + ".assessment..", BASE + ".evidence..",
    BASE + ".mastery..", BASE + ".recommendation..", BASE + ".learner..",
    BASE + ".learning..", BASE + ".security..", BASE + ".observability..",
    BASE + ".ai..", BASE + ".assessmentevaluation..", BASE + ".orchestration.."
  };

  private static final String[] DOMAIN_PACKAGES = {
    "..kafka..", "..cbse..", "..cisce..", "..btech.."
  };

  @ArchTest
  static final ArchRule genericCoreDoesNotDependOnDomainImplementations = noClasses()
      .that().resideInAnyPackage(GENERIC_PACKAGES)
      .should().dependOnClassesThat().resideInAnyPackage(DOMAIN_PACKAGES)
      .because("generic learning behaviour must receive domain context through contracts");

  @ArchTest
  static final ArchRule aiCannotReachAuthoritativeWriters = noClasses()
      .that().resideInAnyPackage(BASE + ".ai..")
      .should().dependOnClassesThat().haveFullyQualifiedName(BASE + ".evidence.EvidenceRepository")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".evidence.EvidenceService")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".mastery.MasteryRepository")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".mastery.MasteryService")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".recommendation.RecommendationRepository")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".recommendation.RecommendationService")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".learning.LearningSessionRepository")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".learning.LearningSessionService")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".learning.ProgressionRepository")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".learning.ProgressionService")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".assessment.AssessmentRepository")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".assessment.DiagnosticSubmissionService")
      .because("AI proposals are non-authoritative; only deterministic application code may write learner state");

  @ArchTest
  static final ArchRule aiCannotUseDatabasePrimitives = noClasses()
      .that().resideInAnyPackage(BASE + ".ai..")
      .should().dependOnClassesThat().areAssignableTo(JdbcTemplate.class)
      .because("AI adapters must not acquire a database handle");

  @ArchTest
  static final ArchRule evaluationGateCannotReachAuthoritativeLearnerStateWriters = noClasses()
      .that().resideInAnyPackage(BASE + ".assessmentevaluation..")
      .should().dependOnClassesThat().haveFullyQualifiedName(BASE + ".evidence.EvidenceRepository")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".evidence.EvidenceService")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".mastery.MasteryRepository")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".mastery.MasteryService")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".assessment.AssessmentRepository")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".assessment.DiagnosticSubmissionService")
      .because("M2-T12 decisions authorize a later workflow but cannot write evidence or mastery");

  @ArchTest
  static final ArchRule agentAdaptersCannotDriveTheControlledWorkflow = noClasses()
      .that().resideInAnyPackage(BASE + ".ai..", BASE + ".assessmentevaluation..",
          BASE + ".diagnosticassessment..")
      .should().dependOnClassesThat().resideInAnyPackage(BASE + ".orchestration..")
      .because("M2-T14 composition is driven by deterministic services; an agent adapter that could "
          + "start or advance a workflow is the unbounded agent-to-agent loop the task forbids");

  @ArchTest
  static final ArchRule theWorkflowReachesAgentsOnlyThroughItsOwnPorts = noClasses()
      .that().resideInAnyPackage(BASE + ".orchestration..")
      .should().dependOnClassesThat().haveFullyQualifiedName(BASE + ".ai.RamalsAiAdaptationClient")
      .orShould().dependOnClassesThat()
          .haveFullyQualifiedName(BASE + ".ai.RamalsAiDiagnosticAssessmentClient")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".ai.RamalsAiAssessmentClient")
      .orShould().dependOnClassesThat().haveFullyQualifiedName(BASE + ".ai.RamalsAiTutorClient")
      .because("steps call the AI plane through WorkflowAgentStep, so the state machine stays "
          + "testable without a model and the plane stays behind an interface the core owns");

  @ArchTest
  static final ArchRule controllersDoNotBypassApplicationServices = noClasses()
      .that().areAnnotatedWith(RestController.class)
      .or().areAnnotatedWith(RestControllerAdvice.class)
      .should().dependOnClassesThat().areAssignableTo(JdbcTemplate.class)
      .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
      .because("HTTP/API code must use application services or ports");

  @ArchTest
  static final ArchRule repositoriesDoNotDependOnWeb = noClasses()
      .that().areAnnotatedWith(Repository.class)
      .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
      .orShould().dependOnClassesThat().resideInAnyPackage("org.springframework.web..")
      .because("persistence must not depend back on the delivery layer");

  @ArchTest
  static final ArchRule servicesDoNotDependOnWeb = noClasses()
      .that().areAnnotatedWith(Service.class)
      .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..")
      .because("application/domain services must not depend on MVC implementation types");

  @ArchTest
  static final ArchRule majorModulesAreAcyclic = slices()
      .matching(BASE + ".(curriculum|assessment|evidence|mastery|recommendation|learner|learning|ai)..")
      .should().beFreeOfCycles()
      .because("major business modules must remain independently evolvable");

  /**
   * Documents the intentionally non-linear dependency model. The acyclicity rule above protects
   * this matrix without pretending that orchestration is a simple one-way chain.
   *
   * <pre>
   * curriculum: identifiers and curriculum facts
   * assessment -> curriculum, learner, evidence, mastery, recommendation
   * evidence -> curriculum/assessment identifiers, observability
   * mastery -> evidence, curriculum, learner
   * recommendation -> mastery, learner, observability
   * learning -> curriculum, learner, mastery, observability
   * ai -> contracts, curriculum facts, learner value objects, observability; never writers/JDBC
   * delivery -> application services/ports; never repositories/JDBC
   * </pre>
   */
  @SuppressWarnings("unused")
  private static final List<String> ALLOWED_DEPENDENCY_MATRIX = List.of(
      "curriculum: identifiers and facts",
      "assessment -> curriculum, learner, evidence, mastery, recommendation",
      "evidence -> curriculum/assessment identifiers, observability",
      "mastery -> evidence, curriculum, learner",
      "recommendation -> mastery, learner, observability",
      "learning -> curriculum, learner, mastery, observability",
      "ai -> contracts, curriculum facts, learner values, observability",
      "delivery -> application services/ports");
}
