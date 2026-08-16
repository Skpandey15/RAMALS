package io.ramals.learningplatform.security;

import io.ramals.learningplatform.observability.TraceContextAccessor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-learner fair-use rate limiting, keyed on the authenticated token subject.
 *
 * <p>This runs <em>inside</em> the security filter chain, after the bearer token has been validated.
 * That placement is the whole point: the subject may only be trusted once the signature, issuer,
 * audience and expiry have been checked. Reading {@code sub} from an unverified token in the
 * pre-authentication filter would let a caller mint themselves an unlimited supply of fresh buckets
 * by varying the claim — or, worse, deliberately exhaust a chosen victim's bucket by borrowing
 * their subject.
 *
 * <p>{@link RateLimitFilter} still runs before authentication and keys on client IP, so an
 * unauthenticated flood is shed without reaching JWT validation. The two tiers answer different
 * questions: that one asks "is this source flooding us", this one asks "is this user over their
 * share".
 */
public class SubjectRateLimitFilter extends OncePerRequestFilter {

  private final TokenBucketRateLimiter limiter;
  private final RateLimitProperties properties;
  private final ObjectMapper objectMapper;
  private final TraceContextAccessor traceContext;

  public SubjectRateLimitFilter(
      TokenBucketRateLimiter limiter, RateLimitProperties properties, ObjectMapper objectMapper,
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
    String subject = authenticatedSubject();
    if (subject == null) {
      // Anonymous traffic was already metered by IP before authentication, and is about to be
      // refused by the authorization rules anyway. Metering it twice would double-charge the
      // permitted-anonymous endpoints for no benefit.
      filterChain.doFilter(request, response);
      return;
    }
    TokenBucketRateLimiter.Decision decision = limiter.tryAcquire(subject);
    if (decision.allowed()) {
      filterChain.doFilter(request, response);
      return;
    }
    RateLimitResponses.writeTooManyRequests(
        request, response, objectMapper, traceContext.traceId(), decision.retryAfterSeconds());
  }

  private static String authenticatedSubject() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return null;
    }
    String name = authentication.getName();
    return name == null || name.isBlank() ? null : name;
  }
}
