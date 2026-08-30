package io.ramals.learningplatform.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Derives the client address used to key pre-authentication rate limiting.
 *
 * <p>{@code X-Forwarded-For} is caller-controlled. Reading its left-most value whenever the header
 * is present — what both limiters did before — lets anyone who can reach the service directly mint a
 * fresh bucket per request by rotating the value, which removes the ceiling entirely rather than
 * weakening it. So the header is consulted only when the immediate peer is a configured trusted
 * proxy, and the chain is then walked from the right, skipping trusted hops, to find the first
 * address no trusted proxy vouched for.
 *
 * <p>Every failure path returns the peer address. That over-throttles a shared proxy at worst; the
 * alternative, falling back to a header value, is the bypass this exists to close.
 *
 * <p>Only {@code X-Forwarded-For} is honoured, because that is what the nginx sidecar and the
 * Traefik ingress in front of this service actually set. {@code Forwarded} and {@code X-Real-IP} are
 * ignored outright — an unused header that is nonetheless trusted is a second way in.
 */
@Component
@EnableConfigurationProperties(TrustedProxyProperties.class)
public class ClientAddressResolver {

  private static final Logger LOGGER = LoggerFactory.getLogger(ClientAddressResolver.class);
  private static final String FORWARDED_FOR = "X-Forwarded-For";

  private final TrustedProxyProperties properties;
  private final List<CidrBlock> trusted;

  public ClientAddressResolver(TrustedProxyProperties properties) {
    this.properties = properties;
    this.trusted = parseTrusted(properties.getTrustedProxies());
  }

  private static List<CidrBlock> parseTrusted(List<String> configured) {
    List<CidrBlock> blocks = new ArrayList<>();
    for (String entry : configured) {
      if (entry == null || entry.isBlank()) {
        continue;
      }
      // A malformed entry is a configuration error, and starting with it silently ignored would
      // mean believing a proxy is trusted when it is not.
      blocks.add(CidrBlock.parse(entry.trim()));
    }
    return List.copyOf(blocks);
  }

  /** The address to key rate limiting on. Never null; never a caller-supplied value from an untrusted peer. */
  public String resolve(HttpServletRequest request) {
    String peer = normalize(request.getRemoteAddr());
    if (peer == null) {
      return "unknown";
    }
    if (!isTrusted(peer)) {
      return peer;
    }
    String header = request.getHeader(FORWARDED_FOR);
    if (header == null || header.isBlank()) {
      return peer;
    }
    if (header.length() > properties.getMaxHeaderLength()) {
      // Neither parsed nor partially honoured: a chain this long is either broken or hostile, and
      // truncating it would still let the attacker choose what survives.
      LOGGER.warn("Discarded an oversized {} header ({} characters)", FORWARDED_FOR, header.length());
      return peer;
    }
    return walkChain(header, peer);
  }

  /**
   * Returns the right-most address in the chain that no trusted proxy vouched for.
   *
   * <p>The right-hand end is the one the closest proxy appended, so it is the only end whose
   * provenance is known. Reading from the left takes whatever the original caller wrote.
   */
  private String walkChain(String header, String peer) {
    String[] hops = header.split(",");
    if (hops.length > properties.getMaxHops()) {
      LOGGER.warn("Discarded a {} chain of {} hops", FORWARDED_FOR, hops.length);
      return peer;
    }
    for (int index = hops.length - 1; index >= 0; index--) {
      String candidate = normalize(hops[index].trim());
      if (candidate == null) {
        // A value that is not an address means the chain cannot be reasoned about from here
        // leftwards, because we can no longer tell which hop appended what.
        return peer;
      }
      if (!isTrusted(candidate)) {
        return candidate;
      }
    }
    // Every hop was a trusted proxy and no client address remains.
    return peer;
  }

  private boolean isTrusted(String address) {
    byte[] bytes = addressBytes(address);
    if (bytes == null) {
      return false;
    }
    for (CidrBlock block : trusted) {
      if (block.contains(bytes)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns a canonical textual address, or null if the value is not one.
   *
   * <p>Canonical matters as much as valid: {@code ::ffff:10.0.0.1} and {@code 10.0.0.1} are the same
   * host, and treating them as different strings would both split rate-limit buckets and let an
   * address slip past a CIDR that should have matched it.
   */
  static String normalize(String raw) {
    byte[] bytes = addressBytes(raw);
    if (bytes == null) {
      return null;
    }
    try {
      return InetAddress.getByAddress(bytes).getHostAddress();
    } catch (UnknownHostException impossible) {
      return null;
    }
  }

  private static byte[] addressBytes(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.trim();
    // Bracketed IPv6, with or without a port: [::1] or [::1]:443.
    if (value.startsWith("[")) {
      int close = value.indexOf(']');
      if (close < 0) {
        return null;
      }
      value = value.substring(1, close);
    } else if (value.chars().filter(character -> character == ':').count() == 1) {
      // Exactly one colon is IPv4 with a port; more than one is bare IPv6.
      value = value.substring(0, value.indexOf(':'));
    }
    int zone = value.indexOf('%');
    if (zone >= 0) {
      value = value.substring(0, zone);
    }
    if (value.isEmpty() || !isLiteralAddress(value)) {
      return null;
    }
    try {
      byte[] bytes = InetAddress.getByName(value).getAddress();
      // Unwrap IPv4-mapped IPv6 so both spellings compare equal.
      if (bytes.length == 16 && isIpv4Mapped(bytes)) {
        return new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
      }
      return bytes;
    } catch (UnknownHostException notAnAddress) {
      return null;
    }
  }

  /**
   * Rejects anything that is not a numeric literal.
   *
   * <p>{@code InetAddress.getByName} resolves hostnames, so without this a forwarding header could
   * make the service perform a DNS lookup per request against a name the caller chose.
   */
  private static boolean isLiteralAddress(String value) {
    boolean hasColon = value.indexOf(':') >= 0;
    if (hasColon) {
      return value.chars().allMatch(c -> c == ':' || c == '.' || Character.digit(c, 16) >= 0);
    }
    return value.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'));
  }

  private static boolean isIpv4Mapped(byte[] bytes) {
    for (int index = 0; index < 10; index++) {
      if (bytes[index] != 0) {
        return false;
      }
    }
    return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
  }

  /** An IPv4 or IPv6 network, matched by prefix on raw address bytes. */
  record CidrBlock(byte[] network, int prefixBits) {

    static CidrBlock parse(String entry) {
      int slash = entry.indexOf('/');
      String host = slash < 0 ? entry : entry.substring(0, slash);
      byte[] bytes = addressBytes(host);
      if (bytes == null) {
        throw new IllegalStateException(
            "ramals.security.forwarding.trusted-proxies contains an invalid entry: " + entry);
      }
      int bits = bytes.length * 8;
      if (slash >= 0) {
        try {
          bits = Integer.parseInt(entry.substring(slash + 1));
        } catch (NumberFormatException notANumber) {
          throw new IllegalStateException(
              "ramals.security.forwarding.trusted-proxies has a non-numeric prefix: " + entry);
        }
        if (bits < 0 || bits > bytes.length * 8) {
          throw new IllegalStateException(
              "ramals.security.forwarding.trusted-proxies has an out-of-range prefix: " + entry);
        }
      }
      return new CidrBlock(bytes, bits);
    }

    boolean contains(byte[] candidate) {
      if (candidate.length != network.length) {
        return false;
      }
      int fullBytes = prefixBits / 8;
      for (int index = 0; index < fullBytes; index++) {
        if (candidate[index] != network[index]) {
          return false;
        }
      }
      int remainingBits = prefixBits % 8;
      if (remainingBits == 0) {
        return true;
      }
      int mask = 0xff << (8 - remainingBits);
      return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
    }
  }
}
