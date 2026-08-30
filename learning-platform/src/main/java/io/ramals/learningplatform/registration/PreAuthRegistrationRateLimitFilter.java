package io.ramals.learningplatform.registration;

import io.ramals.learningplatform.observability.BusinessEventLogger;
import io.ramals.learningplatform.observability.TraceContextAccessor;
import io.ramals.learningplatform.security.RateLimitResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The source-based ceiling in front of the unauthenticated registration route.
 *
 * <p><strong>Why the existing limiter is not enough.</strong> {@code SubjectRateLimitFilter} keys on
 * the authenticated subject. On this route there is no authenticated subject — that is the whole
 * point of the route — so it has nothing to key on and does not constrain it. Without something
 * ahead of authentication, the only cost ceiling on registration would be the per-email one inside
 * the service, which an attacker varies trivially by varying the email.
 *
 * <p><strong>Why it is backed by PostgreSQL rather than a field in this class.</strong> The service
 * runs multiple replicas. An in-process counter constrains one pod, and the next request is load
 * balanced to another, so per-pod state does not throttle a distributed caller — it throttles the
 * accounting. Sharing the counter through the database the request is about to write to anyway costs
 * one upsert and makes the ceiling hold across every replica. §10 requires exactly this: enforcement
 * that changing pods does not bypass.
 *
 * <p><strong>Why not rely on the edge.</strong> A WAF or ingress limiter is a good outer layer and a
 * poor only layer: it is deployment-specific, it is absent in DEV and CI where this behaviour is
 * qualified, and it protects the route rather than the resource. §10 forbids depending on it alone.
 *
 * <p><strong>On the source signal.</strong> {@code X-Forwarded-For}'s leftmost entry is the
 * conventional client address, and it is spoofable by anyone who can reach the service directly.
 * That is acceptable for a cost ceiling and would not be acceptable for an authorization decision,
 * which is not what this makes. The address is hashed before it becomes a bucket key, so no row in
 * {@code identity.abuse_counter} holds an address, and it never reaches a log or a metric label.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 140)
class PreAuthRegistrationRateLimitFilter extends OncePerRequestFilter {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(PreAuthRegistrationRateLimitFilter.class);

  private static final String REGISTRATION_PATH = "/api/v1/registration";
  private static final int SOURCE_LIMIT = 30;
  private static final int SOURCE_WINDOW_SECONDS = 300;

  private final RegistrationRepository registrations;
  private final ObjectMapper objectMapper;
  private final TraceContextAccessor traceContext;

  PreAuthRegistrationRateLimitFilter(RegistrationRepository registrations,
      ObjectMapper objectMapper, TraceContextAccessor traceContext) {
    this.registrations = registrations;
    this.objectMapper = objectMapper;
    this.traceContext = traceContext;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !HttpMethod.POST.matches(request.getMethod())
        || !REGISTRATION_PATH.equals(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    if (!registrations.withinCeiling("registration-source:" + sourceOf(request), SOURCE_LIMIT,
        SOURCE_WINDOW_SECONDS)) {
      // No source value in the event: the whole point of hashing the bucket key is defeated if the
      // address is written to the log line next to it.
      BusinessEventLogger.warn(LOGGER, "registration.source.throttled",
          "Registration request rejected by the pre-authentication source ceiling",
          Map.of("errorCode", "REGISTRATION_RATE_LIMITED", "statusCode", 429,
              "outcome", "REJECTED"));
      RateLimitResponses.writeTooManyRequests(request, response, objectMapper,
          traceContext.traceId(), SOURCE_WINDOW_SECONDS);
      return;
    }
    chain.doFilter(request, response);
  }

  private static String sourceOf(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",", 2)[0].trim();
    }
    return request.getRemoteAddr();
  }
}
