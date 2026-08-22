package io.ramals.learningplatform.execution;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded, externally configurable dispatcher policy. */
@Validated
@ConfigurationProperties(prefix = "ramals.ai.dispatcher")
public class AgentWorkDispatcherProperties {

  private boolean enabled = true;
  @Min(1) @Max(16) private int batchSize = 4;
  @Min(1_000) @Max(300_000) private long leaseMillis = 60_000;
  @Min(1) @Max(20) private int maxAttempts = 5;
  @Min(100) @Max(300_000) private long initialBackoffMillis = 1_000;
  @Min(1_000) @Max(3_600_000) private long maxBackoffMillis = 60_000;

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public int getBatchSize() { return batchSize; }
  public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
  public long getLeaseMillis() { return leaseMillis; }
  public void setLeaseMillis(long leaseMillis) { this.leaseMillis = leaseMillis; }
  public int getMaxAttempts() { return maxAttempts; }
  public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
  public long getInitialBackoffMillis() { return initialBackoffMillis; }
  public void setInitialBackoffMillis(long value) { initialBackoffMillis = value; }
  public long getMaxBackoffMillis() { return maxBackoffMillis; }
  public void setMaxBackoffMillis(long value) { maxBackoffMillis = value; }

  /** Keeps every sequentially processed claim inside its lease at the 12 second call deadline. */
  @AssertTrue(message = "lease-millis must cover batch-size provider deadlines plus 5 seconds")
  public boolean isLeaseLongEnoughForBatch() {
    return leaseMillis >= batchSize * 12_000L + 5_000L;
  }
}
