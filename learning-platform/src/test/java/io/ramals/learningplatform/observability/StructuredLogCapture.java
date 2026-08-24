package io.ramals.learningplatform.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/** Captures the same Logback events and encoder used by the deployed structured console. */
public final class StructuredLogCapture implements AutoCloseable {

  private final LoggerContext context;
  private final Logger logger;
  private final ListAppender<ILoggingEvent> appender;
  private final StructuredLogEncoder encoder;
  private final Object previousEnvironment;

  public StructuredLogCapture(Class<?> loggerType) {
    this(loggerType, new MockEnvironment());
  }

  public StructuredLogCapture(Class<?> loggerType, Environment environment) {
    this.context = (LoggerContext) LoggerFactory.getILoggerFactory();
    this.logger = (Logger) LoggerFactory.getLogger(loggerType);
    this.appender = new ListAppender<>();
    this.encoder = new StructuredLogEncoder();
    this.previousEnvironment = context.getObject(Environment.class.getName());
    context.putObject(Environment.class.getName(), environment);
    encoder.setContext(context);
    encoder.setFormat("logstash");
    encoder.start();
    appender.setContext(context);
    appender.start();
    logger.addAppender(appender);
  }

  public List<ILoggingEvent> events() {
    return List.copyOf(appender.list);
  }

  public String encode(ILoggingEvent event) {
    return new String(encoder.encode(event), StandardCharsets.UTF_8).stripTrailing();
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
    appender.stop();
    encoder.stop();
    if (previousEnvironment == null) {
      context.removeObject(Environment.class.getName());
    } else {
      context.putObject(Environment.class.getName(), previousEnvironment);
    }
  }
}
