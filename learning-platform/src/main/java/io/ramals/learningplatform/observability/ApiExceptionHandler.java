package io.ramals.learningplatform.observability;

import jakarta.servlet.http.HttpServletRequest;
import io.ramals.learningplatform.assessment.AttemptNotFoundException;
import io.ramals.learningplatform.assessment.DiagnosticNotFoundException;
import io.ramals.learningplatform.assessment.InvalidIdempotencyKeyException;
import io.ramals.learningplatform.curriculum.CurriculumNotFoundException;
import io.ramals.learningplatform.learner.LearnerGoalNotSetException;
import io.ramals.learningplatform.learner.UnknownLearningDomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private final TraceContextAccessor traceContext;

  public ApiExceptionHandler(TraceContextAccessor traceContext) {
    this.traceContext = traceContext;
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiProblem> handleDatabaseFailure(DataAccessException exception, HttpServletRequest request) {
    LOGGER.atError()
        .setCause(exception)
        .addKeyValue("errorCode", "DATABASE_OPERATION_FAILED")
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

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiProblem> handleUnexpectedFailure(Exception exception, HttpServletRequest request) {
    LOGGER.atError()
        .setCause(exception)
        .addKeyValue("errorCode", "UNEXPECTED_ERROR")
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
