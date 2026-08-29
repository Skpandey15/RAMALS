package io.ramals.learningplatform.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;

/**
 * Captures everything logged anywhere in the JVM, safely, for tests that must prove nothing leaked.
 *
 * <p>Attaching to the <strong>ROOT</strong> logger at TRACE is deliberate and is the whole point:
 * a test asserting that plaintext never reaches a log has to watch every logger, not the one class
 * it happens to suspect. A future edit that adds a careless statement somewhere else must fail those
 * tests, and it only can if the capture is that wide.
 *
 * <p>Which is exactly why {@link ch.qos.logback.core.read.ListAppender} cannot be used here. Its
 * {@code list} is a plain {@link ArrayList}, and a ROOT-attached appender is fed by every thread in
 * the process — in a Spring test run that includes scheduler threads from cached application
 * contexts, which log on their own timetable. Reading the list while one of them appends throws
 * {@link java.util.ConcurrentModificationException}. That is not hypothetical: it failed CI on
 * `#185` while passing locally, because locally nothing happened to log in that instant.
 *
 * <p>So the backing list is a {@link CopyOnWriteArrayList} and every read hands back a snapshot.
 * Writes are rare (a handful of events per test) and reads are few, which is precisely the shape
 * copy-on-write is for.
 */
public final class RootLogCapture implements AutoCloseable {

  private final Logger root;
  private final Level previousLevel;
  private final CollectingAppender appender = new CollectingAppender();

  public RootLogCapture() {
    this.root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    this.previousLevel = root.getLevel();
    appender.start();
    root.addAppender(appender);
    root.setLevel(Level.TRACE);
  }

  /** A snapshot of what has been logged so far. Never the live list. */
  public List<ILoggingEvent> events() {
    return List.copyOf(appender.events);
  }

  /**
   * Everything logged, flattened to one string for containment assertions.
   *
   * <p>Deliberately a superset: the formatted message, the raw argument array, and any throwable's
   * message. A leak-hunting assertion should fail if the payload appears in <em>any</em> of those,
   * and a test that only read the formatted message would miss a value passed as an argument or
   * carried on an exception.
   */
  public String text() {
    StringBuilder text = new StringBuilder();
    for (ILoggingEvent event : appender.events) {
      text.append(event.getFormattedMessage()).append(' ')
          .append(java.util.Arrays.toString(event.getArgumentArray())).append(' ')
          .append(event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage())
          .append('\n');
    }
    return text.toString();
  }

  @Override
  public void close() {
    root.detachAppender(appender);
    root.setLevel(previousLevel);
    appender.stop();
  }

  private static final class CollectingAppender extends AppenderBase<ILoggingEvent> {

    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
      // Logback may recycle an event's mutable state once the appender returns. Preparing it here
      // fixes the message, arguments and MDC while they are still valid, which is what makes a
      // later read meaningful rather than merely safe.
      event.prepareForDeferredProcessing();
      events.add(event);
    }
  }
}
