package io.ramals.learningplatform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Token-bucket rate-limit configuration. */
@ConfigurationProperties(prefix = "ramals.security.rate-limit")
public class RateLimitProperties {

  private boolean enabled = true;
  private int capacity = 120;
  private double refillPerSecond = 60.0;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getCapacity() {
    return capacity;
  }

  public void setCapacity(int capacity) {
    this.capacity = capacity;
  }

  public double getRefillPerSecond() {
    return refillPerSecond;
  }

  public void setRefillPerSecond(double refillPerSecond) {
    this.refillPerSecond = refillPerSecond;
  }
}
