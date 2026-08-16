package io.ramals.learningplatform.security;

import io.ramals.learningplatform.observability.ApiProblem;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.TraceContextAccessor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Handles filter-chain security denials: records them in {@code audit.security_audit} and returns
 * the standard Problem Details envelope.
 *
 * <p>Two gaps against the Master Plan are closed here. §8 requires a durable security audit; before
 * this, denials existed only in the application log. §7 requires error responses to carry the
 * correlation ids; Spring Security's defaults returned 401 with an empty body, so a learner hitting
 * an auth failure had no support code to quote.
 *
 * <p>Method-security denials do not pass through here — see {@link SecurityAuditRecorder}.
 */
@Component
public class SecurityDenialHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final SecurityAuditRecorder auditRecorder;
  private final TraceContextAccessor traceContext;
  private final ObjectMapper objectMapper;

  public SecurityDenialHandler(
      SecurityAuditRecorder auditRecorder, TraceContextAccessor traceContext,
      ObjectMapper objectMapper) {
    this.auditRecorder = auditRecorder;
    this.traceContext = traceContext;
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    write(request, response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
        "Authentication is required to access this resource.",
        SecurityAuditRecorder.AUTHENTICATION_FAILED);
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
      throws IOException {
    write(request, response, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
        "You are not authorized to perform this operation.",
        SecurityAuditRecorder.AUTHORIZATION_DENIED);
  }

  private void write(
      HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
      String detail, String eventType) throws IOException {
    auditRecorder.recordDenial(eventType, request, status.value(), code);

    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    // The reason code is deliberately coarse: it tells the caller what happened without disclosing
    // which policy matched, which role was missing, or whether the resource exists.
    ApiProblem problem = new ApiProblem(
        "about:blank", status.getReasonPhrase(), status.value(), code, detail,
        CorrelationContext.interactionId(request), traceContext.traceId());
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}
