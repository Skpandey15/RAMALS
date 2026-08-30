package io.ramals.learningplatform.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which immediate peers may speak for a client address.
 *
 * <p>Empty by default, so forwarding headers are ignored until a deployment names the proxies in
 * front of it. Defaulting to the private ranges would be more convenient and would trust anything
 * already inside the cluster network.
 */
@ConfigurationProperties(prefix = "ramals.security.forwarding")
public class TrustedProxyProperties {

  private List<String> trustedProxies = new ArrayList<>();

  /** Chains longer than this are treated as abuse and the header is discarded. */
  private int maxHops = 8;

  /** Bounds parsing work before the chain is split. */
  private int maxHeaderLength = 1024;

  public List<String> getTrustedProxies() {
    return trustedProxies;
  }

  public void setTrustedProxies(List<String> trustedProxies) {
    this.trustedProxies = trustedProxies == null ? new ArrayList<>() : trustedProxies;
  }

  public int getMaxHops() {
    return maxHops;
  }

  public void setMaxHops(int maxHops) {
    this.maxHops = maxHops;
  }

  public int getMaxHeaderLength() {
    return maxHeaderLength;
  }

  public void setMaxHeaderLength(int maxHeaderLength) {
    this.maxHeaderLength = maxHeaderLength;
  }
}
