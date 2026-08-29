package io.ramals.learningplatform.execution.contractb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The commissioning request's bounds are enforced, not merely declared.
 *
 * <p>This exists because they were declared and not enforced. The controller carried a class-level
 * {@code @Validated} and constraint annotations on every field of {@code CommissionRequest}, which
 * looks like validation and is not: {@code @Validated} enables method validation for constraints on
 * the parameters themselves and does not descend into a request body. Without {@code @Valid} on the
 * parameter, every bound was documentation a reader would believe and the runtime would ignore.
 *
 * <p>That is a worse failure than having no bounds at all, and it is invisible to any test that only
 * exercises the happy path — which is why these go through {@link MockMvc} and real Jakarta
 * validation rather than calling the method directly. A direct call bypasses the very layer whose
 * absence was the defect.
 *
 * <p>The assertion that matters most is not the status code. It is that the commissioning service is
 * never reached: commissioning spends real money at a paid provider and starts work that outlives the
 * request, so a malformed body must be refused before anything is admitted or submitted.
 */
class ContractBAdminControllerValidationTests {

  private RecordingCommissioning commissioning;
  private MockMvc mvc;

  /** Counts commissionings without performing any. */
  private static final class RecordingCommissioning extends ContractBCommissioningService {

    private final List<String> commissioned = new ArrayList<>();

    RecordingCommissioning() {
      super(null);
    }

    @Override
    public Commissioned commission(String model, String modelRoute, String prompt,
        int maxOutputTokens) {
      commissioned.add(model + "|" + modelRoute + "|" + maxOutputTokens);
      return new Commissioned("req-commissioned-0001", DurableExecutionState.SUBMITTED);
    }
  }

  /**
   * The production translation of a validation failure into 400.
   *
   * <p>Mirrors {@code ApiExceptionHandler}'s mapping rather than relying on the standalone default,
   * so these tests assert the status the deployed application returns. The real handler needs
   * tracing, metrics and audit collaborators that have nothing to do with the question here.
   */
  @RestControllerAdvice
  static class ValidationAdvice {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    org.springframework.http.ResponseEntity<String> onInvalid(MethodArgumentNotValidException e) {
      return org.springframework.http.ResponseEntity.badRequest().body("VALIDATION_FAILED");
    }
  }

  @BeforeEach
  void setUp() {
    commissioning = new RecordingCommissioning();
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mvc = MockMvcBuilders.standaloneSetup(new ContractBAdminController(commissioning))
        .setControllerAdvice(new ValidationAdvice())
        .setValidator(validator)
        .build();
  }

  private static String body(String model, String modelRoute, String prompt, int maxOutputTokens) {
    return """
        {"model":"%s","modelRoute":"%s","prompt":"%s","maxOutputTokens":%d}"""
        .formatted(model, modelRoute, prompt, maxOutputTokens);
  }

  private void expectRejected(String payload) throws Exception {
    mvc.perform(post("/api/v1/admin/contract-b/executions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest());

    // The point of rejecting early: commissioning spends money and starts durable work. A malformed
    // request must not reach it.
    assertThat(commissioning.commissioned)
        .as("an invalid request must never reach the commissioning service")
        .isEmpty();
  }

  // ================================================================================================
  // Each bound, refused
  // ================================================================================================

  @Test
  @DisplayName("a blank model is refused")
  void aBlankModelIsRefused() throws Exception {
    expectRejected(body("", "diagnostic", "hello", 64));
  }

  @Test
  @DisplayName("a blank modelRoute is refused")
  void aBlankModelRouteIsRefused() throws Exception {
    expectRejected(body("claude-haiku-4-5-20251001", "", "hello", 64));
  }

  @Test
  @DisplayName("a blank prompt is refused")
  void aBlankPromptIsRefused() throws Exception {
    expectRejected(body("claude-haiku-4-5-20251001", "diagnostic", "", 64));
  }

  @Test
  @DisplayName("a prompt beyond 8000 characters is refused")
  void anOversizedPromptIsRefused() throws Exception {
    // The ceiling is a cost and blast-radius bound as much as a size one: the prompt is what the
    // provider is paid to read.
    expectRejected(body("claude-haiku-4-5-20251001", "diagnostic", "p".repeat(8_001), 64));
  }

  @Test
  @DisplayName("a prompt of exactly 8000 characters is accepted")
  void theBoundaryPromptIsAccepted() throws Exception {
    // Asserted so the bound is known to be inclusive rather than assumed. A test that only proves
    // 8001 fails leaves it open whether 8000 does too.
    mvc.perform(post("/api/v1/admin/contract-b/executions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("claude-haiku-4-5-20251001", "diagnostic", "p".repeat(8_000), 64)))
        .andExpect(status().isAccepted());

    assertThat(commissioning.commissioned).hasSize(1);
  }

  @Test
  @DisplayName("a non-positive maxOutputTokens is refused")
  void aNonPositiveTokenCeilingIsRefused() throws Exception {
    for (int tokens : new int[] {0, -1}) {
      commissioning.commissioned.clear();
      expectRejected(body("claude-haiku-4-5-20251001", "diagnostic", "hello", tokens));
    }
  }

  @Test
  @DisplayName("a maxOutputTokens above 64000 is refused")
  void anOversizedTokenCeilingIsRefused() throws Exception {
    expectRejected(body("claude-haiku-4-5-20251001", "diagnostic", "hello", 64_001));
  }

  @Test
  @DisplayName("a model name beyond 128 characters is refused")
  void anOversizedModelIsRefused() throws Exception {
    expectRejected(body("m".repeat(129), "diagnostic", "hello", 64));
  }

  @Test
  @DisplayName("several violations at once are still one refusal, and still no commissioning")
  void multipleViolationsAreRefusedTogether() throws Exception {
    expectRejected(body("", "", "", 0));
  }

  // ================================================================================================
  // The valid case still works
  // ================================================================================================

  @Test
  @DisplayName("a valid request commissions exactly once and reports the state reached")
  void aValidRequestCommissionsOnce() throws Exception {
    mvc.perform(post("/api/v1/admin/contract-b/executions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("claude-haiku-4-5-20251001", "diagnostic", "diagnose this learner", 1_024)))
        .andExpect(status().isAccepted());

    // Exactly one: validation must not have introduced a retry, and the parameters must arrive
    // unaltered.
    assertThat(commissioning.commissioned)
        .containsExactly("claude-haiku-4-5-20251001|diagnostic|1024");
  }

  @Test
  @DisplayName("a malformed body is refused without reaching commissioning")
  void aMalformedBodyIsRefused() throws Exception {
    mvc.perform(post("/api/v1/admin/contract-b/executions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"model\":"))
        .andExpect(status().is4xxClientError());

    assertThat(commissioning.commissioned).isEmpty();
  }
}
