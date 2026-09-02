package io.ramals.learningplatform.registration;

import io.ramals.learningplatform.observability.BusinessEventLogger;
import io.ramals.learningplatform.observability.TraceContextAccessor;
import io.ramals.learningplatform.security.ClientAddressResolver;
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
 * Source ceiling in front of the unauthenticated registration route.
 *
 * <p>{@code SubjectRateLimitFilter} keys on the authenticated subject, and these routes have none,
 * so without something ahead of authentication the only ceiling would be the per-email one inside
 * the service — which an attacker varies by varying the email.
 *
 * <p>Both unauthenticated routes are covered, and the resend route needs it at least as much:
 * registration requires a full valid payload, while a resend needs only an address, so sweeping a
 * list of them is cheaper. One shared source counter rather than one each, because the ceiling is
 * meant to bound what a single source can do to the mail path in total — two separate budgets would
 * let an attacker alternate routes and spend both.
 *
 * <p>Backed by PostgreSQL rather than a field on this bean: the service runs multiple replicas, and
 * a per-pod counter throttles the accounting rather than the caller. The source comes from
 * {@link ClientAddressResolver}, so a forwarding header is honoured only from a configured proxy.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 140)
class PreAuthRegistrationRateLimitFilter extends OncePerRequestFilter {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(PreAuthRegistrationRateLimitFilter.class);

  private static final String REGISTRATION_PATH = "/api/v1/registration";
  private static final String RESEND_PATH = "/api/v1/registration/verification/resend";
  private static final int SOURCE_LIMIT = 30;
  private static final int SOURCE_WINDOW_SECONDS = 300;

  private final AbuseCeiling ceilings;
  private final ObjectMapper objectMapper;
  private final TraceContextAccessor traceContext;
  private final ClientAddressResolver clientAddresses;

  PreAuthRegistrationRateLimitFilter(AbuseCeiling ceilings,
      ObjectMapper objectMapper, TraceContextAccessor traceContext,
      ClientAddressResolver clientAddresses) {
    this.ceilings = ceilings;
    this.objectMapper = objectMapper;
    this.traceContext = traceContext;
    this.clientAddresses = clientAddresses;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!HttpMethod.POST.matches(request.getMethod())) {
      return true;
    }
    String path = request.getRequestURI();
    return !REGISTRATION_PATH.equals(path) && !RESEND_PATH.equals(path);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String source = clientAddresses.resolve(request);
    if (!ceilings.consume("registration-source:" + source, SOURCE_LIMIT, SOURCE_WINDOW_SECONDS)) {
      // No address in the event: hashing the bucket key is pointless if the log line beside it
      // carries the value.
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
}
