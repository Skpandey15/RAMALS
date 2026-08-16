package io.ramals.learningplatform.security;

import io.ramals.learningplatform.observability.TraceContextAccessor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Pre-authentication anti-flood limiting, keyed on client IP. Sheds abusive traffic before any JWT
 * is validated, returning 429 with a Retry-After hint and a Problem Details body carrying the
 * interactionId so a throttled learner can still quote a support code. Runs after the interaction
 * filter so the response is correlated.
 *
 * <p>This tier is deliberately generous: many legitimate users share one egress IP behind a school,
 * office or carrier NAT, and throttling them as a group is not the intent. Per-user fairness is
 * {@link SubjectRateLimitFilter}'s job, applied once the token subject can be trusted.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 150)
public class RateLimitFilter extends OncePerRequestFilter {

  private final TokenBucketRateLimiter limiter;
  private final RateLimitProperties properties;
  private final ObjectMapper objectMapper;
  private final TraceContextAccessor traceContext;

  public RateLimitFilter(
      @Qualifier("ipRateLimiter") TokenBucketRateLimiter limiter,
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
    if (!properties.isEnabled() || RateLimitResponses.isExempt(request)) {
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
    RateLimitResponses.writeTooManyRequests(
        request, response, objectMapper, traceContext.traceId(), retryAfterSeconds);
  }
}
