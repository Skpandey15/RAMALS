package io.ramals.learningplatform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The capture must survive being read while other threads are logging.
 *
 * <p>This is a regression test for a real CI failure, not a hypothetical. A ROOT-attached appender
 * is fed by every thread in the process, and in a Spring test run that includes scheduler threads
 * from cached application contexts. Backed by a plain {@code ArrayList}, reading it while one of
 * those logged threw {@code ConcurrentModificationException} — which passed locally and failed on
 * CI, because locally nothing happened to log in that instant.
 *
 * <p>The leak-hunting tests that depend on this helper assert that a payload is *absent* from the
 * logs. A capture that can throw mid-read turns those into flakes, and a flaky safety test is worse
 * than none: it trains everyone to re-run it.
 */
class RootLogCaptureTests {

  private static final Logger LOGGER = LoggerFactory.getLogger(RootLogCaptureTests.class);

  @Test
  @DisplayName("reading the capture while other threads log does not throw")
  void concurrentAppendDuringReadIsSafe() throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(4);

    try (RootLogCapture capture = new RootLogCapture()) {
      // Four writers, mimicking the scheduler threads that broke this on CI.
      for (int writer = 0; writer < 4; writer++) {
        int id = writer;
        Thread thread = new Thread(() -> {
          try {
            start.await();
            for (int i = 0; i < 500; i++) {
              LOGGER.info("writer {} event {}", id, i);
            }
          } catch (Throwable thrown) {
            failure.compareAndSet(null, thrown);
          } finally {
            done.countDown();
          }
        });
        thread.setDaemon(true);
        thread.start();
      }

      start.countDown();
      // Read continuously while they write. With the old ListAppender this is exactly the race
      // that threw ConcurrentModificationException.
      assertThatCode(() -> {
        for (int read = 0; read < 200; read++) {
          capture.text();
          capture.events();
        }
        done.await(30, TimeUnit.SECONDS);
      }).doesNotThrowAnyException();

      assertThat(failure.get()).as("no writer thread failed").isNull();
      assertThat(capture.events()).as("the capture actually collected events").isNotEmpty();
    }
  }

  @Test
  @DisplayName("the capture sees every logger, not only its own")
  void capturesFromAnyLogger() {
    try (RootLogCapture capture = new RootLogCapture()) {
      LoggerFactory.getLogger("some.entirely.unrelated.Component").warn("CANARY-FROM-ELSEWHERE");

      // The width is the point: a leak-hunting test must fail on a careless statement added
      // anywhere, not only in the class under suspicion.
      assertThat(capture.text()).contains("CANARY-FROM-ELSEWHERE");
    }
  }

  @Test
  @DisplayName("the capture includes arguments and throwable messages, not just the message")
  void capturesArgumentsAndThrowables() {
    try (RootLogCapture capture = new RootLogCapture()) {
      LOGGER.info("value {}", "CANARY-IN-ARGUMENT");
      LOGGER.warn("failed", new IllegalStateException("CANARY-IN-THROWABLE"));

      // A payload can reach a log as an argument or on an exception just as easily as in the
      // message text. Reading only the formatted message would miss both.
      assertThat(capture.text())
          .contains("CANARY-IN-ARGUMENT")
          .contains("CANARY-IN-THROWABLE");
    }
  }

  @Test
  @DisplayName("closing restores the previous root level and stops collecting")
  void closeRestoresTheRootLogger() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
            ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
    ch.qos.logback.classic.Level before = root.getLevel();

    RootLogCapture capture = new RootLogCapture();
    capture.close();

    // Leaving ROOT at TRACE would slow and pollute every test that ran afterwards.
    assertThat(root.getLevel()).isEqualTo(before);

    int collected = capture.events().size();
    LOGGER.info("after close");
    assertThat(capture.events()).hasSize(collected);
  }
}
