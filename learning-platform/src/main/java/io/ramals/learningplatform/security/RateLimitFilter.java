package io.ramals.learningplatform.security;

import io.ramals.learningplatform.observability.ApiProblem;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.TraceContextAccessor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Application-level rate limiting. Sheds abusive traffic before authentication and business work,
 * returning 429 with a Retry-After hint and a Problem Details body that carries the interactionId so
 * a throttled learner can still quote a support code. Runs after the interaction filter so the
 * response is correlated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 150)
public class RateLimitFilter extends OncePerRequestFilter {

  private final TokenBucketRateLimiter limiter;
  private final RateLimitProperties properties;
  private final ObjectMapper objectMapper;
  private final TraceContextAccessor traceContext;

  public RateLimitFilter(
      TokenBucketRateLimiter limiter,
      RateLimitProperties properties,
      ObjectMapper objectMapper,
      TraceContextAccessor traceContext) {
    this.limiter = limiter;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.traceContext = traceContext;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!properties.isEnabled() || isExempt(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    TokenBucketRateLimiter.Decision decision = limiter.tryAcquire(clientKey(request));
    if (decision.allowed()) {
      filterChain.doFilter(request, response);
      return;
    }
    writeTooManyRequests(request, response, decision.retryAfterSeconds());
  }

  private boolean isExempt(HttpServletRequest request) {
    if (HttpMethod.OPTIONS.matches(request.getMethod())) {
      return true;
    }
    String path = request.getRequestURI();
    return path != null && path.startsWith("/actuator/health");
  }

  private String clientKey(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return forwardedFor.split(",", 2)[0].trim();
    }
    return request.getRemoteAddr();
  }

  private void writeTooManyRequests(
      HttpServletRequest request, HttpServletResponse response, long retryAfterSeconds)
      throws IOException {
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
        traceContext.traceId());
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}
