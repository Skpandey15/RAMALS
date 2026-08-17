package io.ramals.learningplatform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The per-request summary line must carry the identifiers used to find it.
 *
 * <p>Existing correlation tests assert on response headers, which were always correct. The log
 * line was not: the summary was emitted from a finally block outside the try-with-resources that
 * held the MDC entries, so by the time it was written interactionId, requestId and http.method had
 * already been removed. Header assertions cannot see that, and the default console pattern renders
 * no MDC at all, so the gap survived until structured logging was enabled in a deployment.
 *
 * <p>These assert on the MDC captured on the event itself, which is what any structured encoder
 * serialises.
 */
@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:requestlog;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class RequestLogCorrelationTests {

  private static final String SUMMARY_MESSAGE = "HTTP request completed";

  @Autowired
  private MockMvc mockMvc;

  private Logger filterLogger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void captureFilterLogging() {
    filterLogger = (Logger) LoggerFactory.getLogger(InteractionIdFilter.class);
    appender = new ListAppender<>();
    appender.start();
    filterLogger.addAppender(appender);
  }

  @AfterEach
  void releaseFilterLogging() {
    filterLogger.detachAppender(appender);
    appender.stop();
  }

  private Map<String, String> summaryContext() {
    List<ILoggingEvent> summaries = appender.list.stream()
        .filter(event -> SUMMARY_MESSAGE.equals(event.getMessage()))
        .toList();
    assertThat(summaries).as("the request should have produced exactly one summary line").hasSize(1);
    return summaries.getFirst().getMDCPropertyMap();
  }

  @Test
  @DisplayName("the summary line carries the interactionId returned to the caller")
  void summaryLineCarriesTheInteractionIdTheCallerWasGiven() throws Exception {
    String echoed = mockMvc.perform(get("/api/v1/learners/me"))
        .andReturn()
        .getResponse()
        .getHeader(CorrelationHeaders.INTERACTION_ID);

    Map<String, String> context = summaryContext();
    assertThat(context.get("interactionId"))
        .as("a support code that matches no log line is not a support code")
        .isEqualTo(echoed);
    assertThat(context).containsKey("requestId").containsKey("http.method");
  }

  @Test
  @DisplayName("a supplied interactionId reaches the summary line unchanged")
  void suppliedInteractionIdReachesTheSummaryLine() throws Exception {
    String supplied = UuidV7.generate().toString();

    mockMvc.perform(get("/api/v1/learners/me").header(CorrelationHeaders.INTERACTION_ID, supplied));

    assertThat(summaryContext().get("interactionId")).isEqualTo(supplied);
  }

  @Test
  @DisplayName("a rejected interactionId is still logged with the generated one")
  void rejectionIsItselfCorrelated() throws Exception {
    mockMvc.perform(get("/api/v1/learners/me").header(CorrelationHeaders.INTERACTION_ID, "nonsense"));

    Map<String, String> context = summaryContext();
    assertThat(context.get("interactionId"))
        .as("the refusal must be findable too, or an invalid header is an unreportable failure")
        .isNotNull()
        .isNotEqualTo("nonsense");
  }

  @Test
  @DisplayName("the summary line carries trace context alongside the interactionId")
  void summaryLineCarriesTraceContext() throws Exception {
    mockMvc.perform(get("/api/v1/learners/me"));

    Map<String, String> context = summaryContext();
    assertThat(context.get("traceId"))
        .as("interactionId finds the request; traceId is what walks it across services")
        .isNotBlank();
    assertThat(context.get("spanId")).isNotBlank();
  }

  @Test
  @DisplayName("identifiers do not leak from one request into the next")
  void identifiersDoNotLeakBetweenRequests() throws Exception {
    mockMvc.perform(get("/api/v1/learners/me"));
    String first = summaryContext().get("interactionId");

    appender.list.clear();
    mockMvc.perform(get("/api/v1/learners/me"));

    assertThat(summaryContext().get("interactionId")).isNotEqualTo(first);
  }
}
