package io.ramals.learningplatform.admin;

import io.ramals.learningplatform.observability.ApiProblem;
import io.ramals.learningplatform.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable HTTP semantics for administrative-domain failures. */
@RestControllerAdvice(basePackageClasses = AdminLearnerController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminApiExceptionHandler {

  @ExceptionHandler(AdminLearnerNotFoundException.class)
  ResponseEntity<ApiProblem> learnerNotFound(
      AdminLearnerNotFoundException exception, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "Learner not found", "ADMIN_LEARNER_NOT_FOUND",
        "The requested learner does not exist.", request);
  }

  @ExceptionHandler(AdminLearnerStateConflictException.class)
  ResponseEntity<ApiProblem> learnerStateConflict(
      AdminLearnerStateConflictException exception, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "Learner state conflict", "ADMIN_LEARNER_STATE_CONFLICT",
        "The requested learner lifecycle transition is not permitted.", request);
  }

  @ExceptionHandler(AdminIdentityProviderException.class)
  ResponseEntity<ApiProblem> identityProviderUnavailable(
      AdminIdentityProviderException exception, HttpServletRequest request) {
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "Identity administration unavailable",
        "ADMIN_IDENTITY_PROVIDER_UNAVAILABLE",
        "The identity administration dependency is currently unavailable.", request);
  }

  private ResponseEntity<ApiProblem> problem(
      HttpStatus status, String title, String code, String detail, HttpServletRequest request) {
    ApiProblem body = new ApiProblem(
        "about:blank",
        title,
        status.value(),
        code,
        detail,
        CorrelationContext.interactionId(request),
        CorrelationContext.currentTraceId());
    return ResponseEntity.status(status).body(body);
  }
}
