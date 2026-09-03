package io.ramals.learningplatform.observability;

import jakarta.servlet.http.HttpServletRequest;
import io.ramals.learningplatform.admin.ContentPublicationException;
import io.ramals.learningplatform.admin.ContentVersionNotFoundException;
import io.ramals.learningplatform.admin.InvalidContentTransitionException;
import io.ramals.learningplatform.assessment.AttemptNotFoundException;
import io.ramals.learningplatform.assessment.DiagnosticNotFoundException;
import io.ramals.learningplatform.assessment.EmptyItemPoolException;
import io.ramals.learningplatform.assessment.InvalidAttemptStateException;
import io.ramals.learningplatform.assessment.InvalidIdempotencyKeyException;
import io.ramals.learningplatform.assessment.InvalidSubmissionException;
import io.ramals.learningplatform.assessment.UnknownAssessmentItemException;
import io.ramals.learningplatform.curriculum.CurriculumNotFoundException;
import io.ramals.learningplatform.learner.LearnerGoalNotSetException;
import io.ramals.learningplatform.learner.UnknownLearningDomainException;
import io.ramals.learningplatform.learning.InvalidSessionTransitionException;
import io.ramals.learningplatform.learning.LearningSessionNotFoundException;
import io.ramals.learningplatform.learning.SessionConflictException;
import io.ramals.learningplatform.content.ApprovalRequestException;
import io.ramals.learningplatform.registration.RegistrationException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import io.ramals.learningplatform.security.SecurityAuditRecorder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private final TraceContextAccessor traceContext;
  private final MeterRegistry meterRegistry;
  private final SecurityAuditRecorder securityAuditRecorder;

  public ApiExceptionHandler(
      TraceContextAccessor traceContext, MeterRegistry meterRegistry,
      SecurityAuditRecorder securityAuditRecorder) {
    this.traceContext = traceContext;
    this.meterRegistry = meterRegistry;
    this.securityAuditRecorder = securityAuditRecorder;
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiProblem> handleDatabaseFailure(DataAccessException exception, HttpServletRequest request) {
    LOGGER.atError()
        .setCause(exception)
        .addKeyValue("errorCode", "DATABASE_OPERATION_FAILED")
        .addKeyValue("exceptionType", exception.getClass().getName())
        .addKeyValue("operation", "database.operation")
        .log("Database operation failed");
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Database operation failed",
        "DATABASE_OPERATION_FAILED",
        "The operation could not be completed.",
        request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiProblem> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
    LOGGER.atWarn()
        .addKeyValue("errorCode", "ACCESS_DENIED")
        .addKeyValue("operation", "authorization.check")
        .log("Authorization denied");
    // Method-security denials land here rather than in the filter chain's AccessDeniedHandler, so
    // this is the only place a role-based refusal can be audited (Master Plan §8).
    securityAuditRecorder.recordDenial(
        SecurityAuditRecorder.AUTHORIZATION_DENIED, request, HttpStatus.FORBIDDEN.value(),
        "ACCESS_DENIED");
    return problem(
        HttpStatus.FORBIDDEN,
        "Access denied",
        "ACCESS_DENIED",
        "You are not authorized to perform this operation.",
        request);
  }

  @ExceptionHandler(CurriculumNotFoundException.class)
  ResponseEntity<ApiProblem> handleCurriculumNotFound(
      CurriculumNotFoundException exception,
      HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "Curriculum not found",
        "CURRICULUM_NOT_FOUND",
        "The requested published curriculum resource does not exist.",
        request);
  }

  @ExceptionHandler(LearnerGoalNotSetException.class)
  ResponseEntity<ApiProblem> handleGoalNotSet(
      LearnerGoalNotSetException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "Learning goal not set",
        "GOAL_NOT_SET",
        "No learning goal has been set for this learner.",
        request);
  }

  @ExceptionHandler(UnknownLearningDomainException.class)
  ResponseEntity<ApiProblem> handleUnknownDomain(
      UnknownLearningDomainException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Unknown learning domain",
        "UNKNOWN_LEARNING_DOMAIN",
        "The requested learning domain does not exist or is not active.",
        request);
  }

  @ExceptionHandler(DiagnosticNotFoundException.class)
  ResponseEntity<ApiProblem> handleDiagnosticNotFound(
      DiagnosticNotFoundException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "Diagnostic not found",
        "DIAGNOSTIC_NOT_FOUND",
        "No published diagnostic is available for the requested domain.",
        request);
  }

  @ExceptionHandler(AttemptNotFoundException.class)
  ResponseEntity<ApiProblem> handleAttemptNotFound(
      AttemptNotFoundException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "Attempt not found",
        "ATTEMPT_NOT_FOUND",
        "The requested assessment attempt does not exist.",
        request);
  }

  @ExceptionHandler(InvalidAttemptStateException.class)
  ResponseEntity<ApiProblem> handleInvalidAttemptState(
      InvalidAttemptStateException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "Invalid attempt state",
        "INVALID_ATTEMPT_STATE",
        "The attempt is not open for submission.",
        request);
  }

  @ExceptionHandler(UnknownAssessmentItemException.class)
  ResponseEntity<ApiProblem> handleUnknownAssessmentItem(
      UnknownAssessmentItemException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Unknown assessment item",
        "UNKNOWN_ASSESSMENT_ITEM",
        "A submitted response references an item this attempt did not present.",
        request);
  }

  /**
   * A published assessment version with nothing selectable in it. V017 makes that unreachable
   * through publication, so this is a broken invariant rather than anything the learner did --
   * hence a 500 with a code an operator can search for, not a 4xx blaming the request.
   */
  @ExceptionHandler(EmptyItemPoolException.class)
  ResponseEntity<ApiProblem> handleEmptyItemPool(
      EmptyItemPoolException exception, HttpServletRequest request) {
    LOGGER.atError()
        .setCause(exception)
        .addKeyValue("errorCode", "DIAGNOSTIC_FORM_UNAVAILABLE")
        .addKeyValue("operation", "assessment.form.selected")
        .log("Diagnostic form could not be assembled");
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Diagnostic unavailable",
        "DIAGNOSTIC_FORM_UNAVAILABLE",
        "The diagnostic could not be assembled.",
        request);
  }

  @ExceptionHandler(InvalidSubmissionException.class)
  ResponseEntity<ApiProblem> handleInvalidSubmission(
      InvalidSubmissionException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Invalid submission",
        "INVALID_SUBMISSION",
        "The submission could not be processed as provided.",
        request);
  }

  @ExceptionHandler(InvalidIdempotencyKeyException.class)
  ResponseEntity<ApiProblem> handleInvalidIdempotencyKey(
      InvalidIdempotencyKeyException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Invalid request",
        "IDEMPOTENCY_KEY_REQUIRED",
        "A valid Idempotency-Key header is required for this operation.",
        request);
  }

  @ExceptionHandler(LearningSessionNotFoundException.class)
  ResponseEntity<ApiProblem> handleSessionNotFound(
      LearningSessionNotFoundException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "Session not found",
        "SESSION_NOT_FOUND",
        "The requested learning session does not exist.",
        request);
  }

  @ExceptionHandler(InvalidSessionTransitionException.class)
  ResponseEntity<ApiProblem> handleInvalidSessionTransition(
      InvalidSessionTransitionException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Invalid session transition",
        "INVALID_SESSION_TRANSITION",
        "The command is not valid for the session's current state.",
        request);
  }

  @ExceptionHandler(SessionConflictException.class)
  ResponseEntity<ApiProblem> handleSessionConflict(
      SessionConflictException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "Session conflict",
        "SESSION_CONFLICT",
        "The session was modified concurrently; reload and retry.",
        request);
  }

  @ExceptionHandler(ContentVersionNotFoundException.class)
  ResponseEntity<ApiProblem> handleContentVersionNotFound(
      ContentVersionNotFoundException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "Content version not found",
        "CONTENT_VERSION_NOT_FOUND",
        "The requested curriculum version does not exist.",
        request);
  }

  @ExceptionHandler(InvalidContentTransitionException.class)
  ResponseEntity<ApiProblem> handleInvalidContentTransition(
      InvalidContentTransitionException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Invalid content transition",
        "INVALID_CONTENT_TRANSITION",
        "The lifecycle command is not valid for the version's current status.",
        request);
  }

  @ExceptionHandler(ContentPublicationException.class)
  ResponseEntity<ApiProblem> handleContentPublication(
      ContentPublicationException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Content cannot be published",
        "CONTENT_NOT_PUBLISHABLE",
        "The curriculum version does not satisfy the content-integrity rules for publication.",
        request);
  }

  @ExceptionHandler(ApprovalRequestException.class)
  ResponseEntity<ApiProblem> handleApprovalRequest(
      ApprovalRequestException exception, HttpServletRequest request) {
    HttpStatus status = switch (exception.code()) {
      case "APPROVAL_NOT_FOUND", "CANDIDATE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
      case "IDEMPOTENCY_KEY_REQUIRED", "REVIEW_REASON_REQUIRED" -> HttpStatus.BAD_REQUEST;
      case "CANDIDATE_NOT_ELIGIBLE", "APPROVAL_ALREADY_EXISTS", "APPROVAL_STATE_CONFLICT",
          "IDEMPOTENCY_CONFLICT", "PROMOTION_CONFLICT" -> HttpStatus.CONFLICT;
      case "CANCEL_NOT_AUTHORIZED" -> HttpStatus.FORBIDDEN;
      default -> HttpStatus.UNPROCESSABLE_ENTITY;
    };
    return problem(status, "Approval request rejected", exception.code(),
        "The approval command could not be completed.", request);
  }

  /**
   * Registration and verification rejections.
   *
   * <p>Unlike the sibling domains above, this one carries its own status and its own learner-facing
   * detail. The reason is the count: the package rejects for well over a dozen distinct causes, and a
   * per-code {@code case} here would be the place the mapping rots — a code added at the throw site
   * and forgotten in this switch would silently answer with the wrong status. Keeping the pair
   * together in {@link RegistrationException} makes that impossible, and leaves this method total.
   *
   * <p>The detail is deliberately coarser than the code for the enumeration-sensitive cases; see
   * {@link RegistrationException#detail()}. {@code Retry-After} is set only for the codes that are
   * throttles, so a client can back off correctly rather than retry immediately against a ceiling.
   */
  @ExceptionHandler(RegistrationException.class)
  ResponseEntity<ApiProblem> handleRegistrationFailure(
      RegistrationException exception, HttpServletRequest request) {
    ResponseEntity<ApiProblem> response = problem(
        exception.status(),
        "Registration request rejected",
        exception.code(),
        exception.detail(),
        request);
    long retryAfter = exception.retryAfterSeconds();
    if (retryAfter <= 0) {
      return response;
    }
    return ResponseEntity.status(exception.status())
        .headers(headers -> headers.addAll(response.getHeaders()))
        .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter))
        .body(response.getBody());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ApiProblem> handleIllegalArgument(
      IllegalArgumentException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Invalid request",
        "VALIDATION_FAILED",
        "The request contained an invalid value.",
        request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiProblem> handleValidationFailure(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    String detail = exception.getBindingResult().getFieldErrors().stream()
        .map(error -> error.getField() + " " + error.getDefaultMessage())
        .sorted()
        .reduce((left, right) -> left + "; " + right)
        .orElse("The request payload failed validation.");
    return problem(
        HttpStatus.BAD_REQUEST,
        "Invalid request",
        "VALIDATION_FAILED",
        detail,
        request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiProblem> handleUnreadableBody(
      HttpMessageNotReadableException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Invalid request",
        "MALFORMED_REQUEST",
        "The request body could not be parsed.",
        request);
  }

  /**
   * A route that does not exist is a 404, not a server failure.
   *
   * <p>Spring raises {@link NoResourceFoundException} for an unmapped path, which fell through to the
   * catch-all below and was reported as {@code 500 UNEXPECTED_ERROR} with a stack trace logged at
   * ERROR. M1-T18 found it while diagnosing the missing tutor endpoint: the symptom of a route that
   * had never been written was indistinguishable from the platform breaking.
   *
   * <p>Three things that costs. A client cannot tell "you asked for something that is not here" from
   * "we failed" — and {@code tutorApi.ts} maps any non-OK response onto AI_TRANSPORT_FAILURE, so a
   * missing route reads to the learner as an AI outage. Every scan for a path an attacker guesses
   * lands in the error log at ERROR with a stack trace, which is both noise and a signal to whoever
   * is guessing. And the error counter attributes probing traffic to UNEXPECTED_ERROR, burying real
   * failures among 404s.
   *
   * <p>The detail deliberately does not echo the path: a 404 should confirm nothing about what does
   * exist. The interaction id is still returned, so a learner who hits one can still quote a code.
   */
  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiProblem> handleNoResourceFound(
      NoResourceFoundException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "Not found",
        "RESOURCE_NOT_FOUND",
        "The requested resource does not exist.",
        request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiProblem> handleUnexpectedFailure(Exception exception, HttpServletRequest request) {
    LOGGER.atError()
        .setCause(exception)
        .addKeyValue("errorCode", "UNEXPECTED_ERROR")
        .addKeyValue("exceptionType", exception.getClass().getName())
        .addKeyValue("operation", "http.request")
        .log("Unexpected request failure");
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Unexpected error",
        "UNEXPECTED_ERROR",
        "The operation could not be completed.",
        request);
  }

  private ResponseEntity<ApiProblem> problem(
      HttpStatus status,
      String title,
      String code,
      String detail,
      HttpServletRequest request) {
    if (!"DATABASE_OPERATION_FAILED".equals(code) && !"ACCESS_DENIED".equals(code)
        && !"UNEXPECTED_ERROR".equals(code)) {
      // Domain/input rejections are expected outcomes. Emit one structured warning at the
      // authoritative HTTP boundary; individual controllers/services do not log the same error.
      BusinessEventLogger.warn(LOGGER, "http.request.rejected", "HTTP request rejected",
          java.util.Map.of(
              "errorCode", code,
              "statusCode", status.value(),
              "outcome", "REJECTED"));
    }
    // One counter for every handled failure, tagged by stable error code and status, so support and
    // dashboards can find forced failures at the security, service, and database layers by class.
    meterRegistry.counter("ramals.api.errors", "code", code, "status", String.valueOf(status.value()))
        .increment();
    ApiProblem body = new ApiProblem(
        "about:blank",
        title,
        status.value(),
        code,
        detail,
        CorrelationContext.interactionId(request),
        traceContext.traceId());
    return ResponseEntity.status(status)
        .header(CorrelationHeaders.INTERACTION_ID, body.interactionId())
        .header(CorrelationHeaders.TRACE_ID, body.traceId())
        .body(body);
  }
}
