package io.ramals.learningplatform.security;

import io.ramals.learningplatform.observability.ApiProblem;
import io.ramals.learningplatform.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared 429 response for both rate-limit tiers, so a throttled caller cannot tell which tier shed
 * the request. The body carries the interactionId, so a throttled learner can still quote a support
 * code.
 */
final class RateLimitResponses {

  private RateLimitResponses() {
  }

  static void writeTooManyRequests(
      HttpServletRequest request, HttpServletResponse response, ObjectMapper objectMapper,
      String traceId, long retryAfterSeconds) throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
    ApiProblem problem = new ApiProblem(
        "about:blank",
        "Too many requests",
        HttpStatus.TOO_MANY_REQUESTS.value(),
        "RATE_LIMITED",
        "Request rate limit exceeded; retry after " + retryAfterSeconds + " seconds.",
        CorrelationContext.interactionId(request),
        traceId);
    objectMapper.writeValue(response.getOutputStream(), problem);
  }

  /** Preflight and liveness/readiness probes are never throttled. */
  static boolean isExempt(HttpServletRequest request) {
    if (HttpMethod.OPTIONS.matches(request.getMethod())) {
      return true;
    }
    String path = request.getRequestURI();
    return path != null && path.startsWith("/actuator/health");
  }
}
