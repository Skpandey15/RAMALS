package io.ramals.learningplatform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M2-ADR-034 Amendment 2, Step 2 boundaries, executable.
 *
 * <ul>
 *   <li>{@code HypothesisDiscriminationCalculatorV1} is pure: no database, no AI/MCP/orchestration,
 *       no write service of any kind, no selector (§S/§T).
 *   <li>It is inert: nothing in {@code DIAGNOSTIC_SELECTION_V1}-{@code V5} depends on the {@code
 *       hypothesisdiscrimination} package directly, and the package depends on no selector (§O).
 *       {@code DIAGNOSTIC_SELECTION_V6} (M2-ADR-034 Amendment 3) is the sole, deliberate exception --
 *       {@code HypothesisDiscriminationDiagnosticSelector} alone calls
 *       {@code HYPOTHESIS_DISCRIMINATION_V1} verbatim; V1-V5 stay exactly as inert as before.
 *   <li>It takes no M2-ADR-033 misconception-graph input, and no M2-ADR-032 advisory-proposal
 *       input (§E/§S: an accepted proposal never enters this package's candidate set).
 *   <li>No production code anywhere defines an {@code INFORMATION_GAIN_V1} identifier -- the sole
 *       Step-2 engine identifier is {@code HYPOTHESIS_DISCRIMINATION_V1} (Amendment 2 §A).
 * </ul>
 */
@Tag("architecture")
class HypothesisDiscriminationArchitectureGuardrailTests {

  private static final String BASE = "io.ramals.learningplatform";
  private static final String PACKAGE = BASE + ".assessment.hypothesisdiscrimination";
  private static final String CALCULATOR = PACKAGE + ".HypothesisDiscriminationCalculatorV1";

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE);

  @Test
  @DisplayName("the pure calculator has no JdbcTemplate / repository dependency")
  void calculatorHasNoPersistenceDependency() {
    noClasses()
        .that()
        .haveFullyQualifiedName(CALCULATOR)
        .should()
        .dependOnClassesThat()
        .haveNameMatching(".*\\bJdbcTemplate$")
        .orShould()
        .dependOnClassesThat()
        .haveSimpleNameEndingWith("Repository")
        .because(
            "Amendment 2 §P/§T: the pure calculator must have no dependency on JdbcTemplate or "
                + "repositories -- there is no persistence, and this is deterministic compute-on-read")
        .check(classes);
  }

  @Test
  @DisplayName("the pure calculator never calls a JdbcTemplate method directly")
  void calculatorNeverCallsJdbcTemplate() {
    DescribedPredicate<JavaCall<?>> aJdbcCall = new DescribedPredicate<>("any JdbcTemplate method") {
      @Override
      public boolean test(JavaCall<?> call) {
        return call.getTargetOwner().getFullName().equals("org.springframework.jdbc.core.JdbcTemplate");
      }
    };
    noClasses()
        .that()
        .haveFullyQualifiedName(CALCULATOR)
        .should()
        .callMethodWhere(aJdbcCall)
        .because("the pure calculator must have no side effects of any kind")
        .check(classes);
  }

  @Test
  @DisplayName("the package reaches no AI, MCP, or orchestration code")
  void packageCannotReachAiOrMcpOrOrchestration() {
    noClasses()
        .that()
        .resideInAPackage(PACKAGE)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(BASE + ".ai..", BASE + ".orchestration..", BASE + ".mcp..")
        .because(
            "HYPOTHESIS_DISCRIMINATION_V1 is deterministic Java only -- no LLM, no LangGraph, no MCP "
                + "computes or adjusts it (Amendment 2 §S)")
        .check(classes);
  }

  @Test
  @DisplayName("the package takes no M2-ADR-032 advisory-proposal input")
  void packageCannotReachAdvisoryProbeProposal() {
    noClasses()
        .that()
        .resideInAPackage(PACKAGE)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(BASE + ".diagnosticassessment..")
        .because(
            "Amendment 2 §E/§S: an accepted M2-ADR-032 advisory proposal stays within its own "
                + "advisory/audit boundary and is never eligible input to HYPOTHESIS_DISCRIMINATION_V1 "
                + "-- promoting it would require a separate, explicit ADR")
        .check(classes);
  }

  @Test
  @DisplayName("the package does not reach any DIAGNOSTIC_SELECTION selector or probe resolution")
  void packageCannotReachSelectorsOrProbeResolution() {
    // HypothesisDrivenProbeDiagnosticSelector is the one documented exception: Amendment 1 §H
    // (reused verbatim by Amendment 2 §K) mandates reusing its frozen RELATIONSHIP_TYPE_PRIORITY
    // constant rather than redefining the same order a second time.
    // packageOnlyReadsThePriorityConstantOfV5, below, proves that reuse is field-access-only -- no
    // method of V5 is ever called from this package.
    noClasses()
        .that()
        .resideInAPackage(PACKAGE)
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
        .haveFullyQualifiedName(BASE + ".assessment.ProbeRelationshipResolver")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.ProbeRelationshipService")
        .because(
            "Amendment 2 §O: Step 2 is inert -- it computes a score, it does not decide or execute "
                + "the next probe, and no DIAGNOSTIC_SELECTION_V1-V5 code is touched. "
                + "HypothesisDiscriminationDiagnosticSelector (DIAGNOSTIC_SELECTION_V6, Amendment 3) "
                + "is deliberately not in this list -- it is the one authorized orchestrator that "
                + "reads this package")
        .check(classes);
  }

  @Test
  @DisplayName("the package only reads V5's frozen priority constant -- never calls any of its methods")
  void packageOnlyReadsThePriorityConstantOfV5() {
    DescribedPredicate<JavaCall<?>> aV5MethodCall = new DescribedPredicate<>(
        "a method of HypothesisDrivenProbeDiagnosticSelector") {
      @Override
      public boolean test(JavaCall<?> call) {
        return call.getTargetOwner().getFullName()
            .equals(BASE + ".assessment.HypothesisDrivenProbeDiagnosticSelector");
      }
    };
    noClasses()
        .that()
        .resideInAPackage(PACKAGE)
        .should()
        .callMethodWhere(aV5MethodCall)
        .because(
            "Amendment 2 §K's reuse of the hypothesis canonical order (Amendment 1 §H, itself built "
                + "from RELATIONSHIP_TYPE_PRIORITY) is a constant read, not behavioural coupling -- "
                + "calling adjustForHypothesisProbe() or any other V5 method from this package would "
                + "be exactly the runtime wiring Step 2 does not authorize")
        .check(classes);
  }

  @Test
  @DisplayName("no DIAGNOSTIC_SELECTION_V1-V5 selector depends on the hypothesis-discrimination package")
  void selectorsDoNotDependOnHypothesisDiscrimination() {
    // HypothesisDiscriminationDiagnosticSelector (DIAGNOSTIC_SELECTION_V6, M2-ADR-034 Amendment 3)
    // is deliberately absent from this list -- it is the one authorized orchestrator that reads
    // HYPOTHESIS_DISCRIMINATION_V1; see DiagnosticSelectionV6ArchitectureGuardrailTests for its own
    // isolation guarantees.
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
        .or()
        .haveSimpleName("ProbeRelationshipService")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(PACKAGE)
        .because(
            "Amendment 2 §O: no DIAGNOSTIC_SELECTION_V1-V5 engine reads HYPOTHESIS_DISCRIMINATION_V1 "
                + "directly -- only DIAGNOSTIC_SELECTION_V6's own orchestrator does, and V1-V5 stay "
                + "exactly as they were before Amendment 3")
        .check(classes);
  }

  @Test
  @DisplayName("the package takes no M2-ADR-033 misconception-graph input")
  void packageCannotReachMisconceptionGraph() {
    noClasses()
        .that()
        .resideInAPackage(PACKAGE)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(BASE + ".assessment.misconceptiongraph")
        .because(
            "Amendment 2 §M: MisconceptionGraphQueryService -- X -- HYPOTHESIS_DISCRIMINATION_V1; "
                + "no graph edge, and no edge-type weight, participates")
        .check(classes);
  }

  @Test
  @DisplayName("the frozen field carries exactly HYPOTHESIS_DISCRIMINATION_V1, never INFORMATION_GAIN_V1")
  void engineVersionIsExactlyTheFrozenIdentifier() {
    assertThat(io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationCalculatorV1.ENGINE_VERSION)
        .isEqualTo("HYPOTHESIS_DISCRIMINATION_V1");
  }

  @Test
  @DisplayName("no main source file defines an INFORMATION_GAIN_V1 string-literal constant")
  void noProductionSourceDefinesInformationGainV1() throws IOException {
    // Quoted-string form only -- an English mention in a javadoc comment (e.g. "no
    // INFORMATION_GAIN_V1 ... exist here", explaining what is deliberately absent) is not a
    // production constant and must not fail this guard.
    Pattern literal = Pattern.compile("\"INFORMATION_GAIN_V1\"");
    Path root = Path.of("src", "main", "java");
    if (!Files.isDirectory(root)) {
      root = Path.of("learning-platform", "src", "main", "java");
    }
    List<String> offending = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        Matcher matcher = literal.matcher(Files.readString(file, StandardCharsets.UTF_8));
        if (matcher.find()) {
          offending.add(file.toString());
        }
      }
    }
    assertThat(offending)
        .as("no main source file may reference the identifier INFORMATION_GAIN_V1 -- Amendment 2 "
            + "froze HYPOTHESIS_DISCRIMINATION_V1 instead")
        .isEmpty();
  }
}
