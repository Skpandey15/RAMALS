package io.ramals.learningplatform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class BusinessEventLoggerTests {

  private static final Logger LOGGER = LoggerFactory.getLogger(BusinessEventLoggerTests.class);

  @AfterEach
  void clearCorrelation() {
    MDC.clear();
  }

  @Test
  void explicitCorrelationFieldsUseTheMdcOwnerWhenARequestAlreadyHasThem() {
    MDC.put("interactionId", "request-interaction");
    MDC.put("traceId", "request-trace");
    MDC.put("spanId", "request-span");

    try (StructuredLogCapture logs = new StructuredLogCapture(BusinessEventLoggerTests.class)) {
      BusinessEventLogger.info(
          LOGGER,
          "test.correlation",
          "correlation event",
          Map.of(
              "interactionId", "caller-interaction",
              "traceId", "caller-trace",
              "spanId", "caller-span",
              "requestId", "request-1"));

      String json = eventJson(logs, "correlation event");
      assertThat(json).contains("\"interactionId\":\"request-interaction\"");
      assertThat(json).contains("\"traceId\":\"request-trace\"");
      assertThat(json).contains("\"spanId\":\"request-span\"");
      assertOne(json, "interactionId");
      assertOne(json, "traceId");
      assertOne(json, "spanId");
      assertOne(json, "requestId");
    }
  }

  @Test
  void aWorkerWithoutInheritedMdcCanBindDurableCorrelationForOneEvent() throws Exception {
    String interactionId = "worker-interaction";
    String traceId = "worker-trace";

    try (StructuredLogCapture logs = new StructuredLogCapture(BusinessEventLoggerTests.class);
        ExecutorService worker = Executors.newSingleThreadExecutor()) {
      worker
          .submit(
              () ->
                  BusinessEventLogger.info(
                      LOGGER,
                      "test.async",
                      "async correlation event",
                      Map.of("interactionId", interactionId, "traceId", traceId)))
          .get();

      String json = eventJson(logs, "async correlation event");
      assertThat(json).contains("\"interactionId\":\"" + interactionId + "\"");
      assertThat(json).contains("\"traceId\":\"" + traceId + "\"");
      assertOne(json, "interactionId");
      assertOne(json, "traceId");
    }
    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }

  @Test
  void exceptionEventRetainsCorrelationAndRedactsSensitiveFields() {
    MDC.put("interactionId", "error-interaction");
    MDC.put("traceId", "error-trace");

    try (StructuredLogCapture logs = new StructuredLogCapture(BusinessEventLoggerTests.class)) {
      BusinessEventLogger.error(
          LOGGER,
          "test.failure",
          "correlation failure",
          new IllegalStateException("controlled failure"),
          Map.of(
              "interactionId", "caller-interaction",
              "traceId", "caller-trace",
              "token", "secret-token",
              "answer", "learner-answer"));

      String json = eventJson(logs, "correlation failure");
      assertOne(json, "interactionId");
      assertOne(json, "traceId");
      assertThat(json).contains("[REDACTED]");
      assertThat(json).doesNotContain("secret-token", "learner-answer");
      assertThat(json).contains("controlled failure");
    }
  }

  private static String eventJson(StructuredLogCapture logs, String message) {
    ILoggingEvent event =
        logs.events().stream()
            .filter(candidate -> message.equals(candidate.getMessage()))
            .findFirst()
            .orElseThrow();
    return logs.encode(event);
  }

  private static void assertOne(String json, String key) {
    assertThat(count(json, "\"" + key + "\"")).as(key + " must have one JSON owner").isEqualTo(1);
  }

  private static int count(String value, String needle) {
    int count = 0;
    for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0; offset += needle.length()) {
      count++;
    }
    return count;
  }
}
