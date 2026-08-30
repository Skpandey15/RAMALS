package io.ramals.learningplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stops the codebase regressing to trusting a caller-supplied client address.
 *
 * <p>Both rate limiters previously read the left-most {@code X-Forwarded-For} value whenever the
 * header was present, which let anyone able to reach the service directly mint a fresh bucket per
 * request. Fixing the two call sites does not stop a third being written, so the rule is enforced
 * on the source: only {@link ClientAddressResolver} may read a forwarding header at all.
 */
class ForwardingHeaderSecurityContractTests {

  private static final Path MAIN_SOURCES =
      Path.of("src/main/java/io/ramals/learningplatform");

  private static final String RESOLVER = "ClientAddressResolver.java";

  private static final List<String> FORWARDING_HEADERS =
      List.of("X-Forwarded-For", "X-Real-IP", "X-Client-IP", "Forwarded", "True-Client-IP",
          "CF-Connecting-IP");

  private static String withoutComments(String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
  }

  private static List<Path> mainSources() throws Exception {
    try (Stream<Path> paths = Files.walk(MAIN_SOURCES)) {
      return paths.filter(path -> path.toString().endsWith(".java")).toList();
    }
  }

  @Test
  @DisplayName("only the resolver reads a forwarding header")
  void onlyTheResolverReadsForwardingHeaders() throws Exception {
    for (Path source : mainSources()) {
      if (source.getFileName().toString().equals(RESOLVER)) {
        continue;
      }
      String code = withoutComments(Files.readString(source));
      for (String header : FORWARDING_HEADERS) {
        assertThat(code)
            .as("%s reads %s directly; derive the client address through ClientAddressResolver",
                source.getFileName(), header)
            .doesNotContain("\"" + header + "\"");
      }
    }
  }

  @Test
  @DisplayName("only the resolver reads the raw peer address")
  void onlyTheResolverReadsRemoteAddr() throws Exception {
    for (Path source : mainSources()) {
      if (source.getFileName().toString().equals(RESOLVER)) {
        continue;
      }
      String code = withoutComments(Files.readString(source));
      // getRemoteAddr is not itself dangerous, but a second derivation path is how the two
      // implementations drifted apart the first time.
      assertThat(code)
          .as("%s reads getRemoteAddr directly; use ClientAddressResolver", source.getFileName())
          .doesNotContain("getRemoteAddr()");
    }
  }

  @Test
  @DisplayName("the resolver ignores forwarding headers until a proxy is configured")
  void trustedProxiesDefaultToEmpty() {
    // Defaulting to the private ranges would be convenient and would trust anything already inside
    // the cluster network.
    assertThat(new TrustedProxyProperties().getTrustedProxies()).isEmpty();
  }

  @Test
  @DisplayName("forwarding parsing is bounded by default")
  void forwardingParsingIsBounded() {
    TrustedProxyProperties defaults = new TrustedProxyProperties();
    assertThat(defaults.getMaxHops()).isBetween(1, 32);
    assertThat(defaults.getMaxHeaderLength()).isBetween(64, 8192);
  }

  @Test
  @DisplayName("no client address reaches a log, a metric label or an audit field")
  void clientAddressesAreNeverRecorded() throws Exception {
    for (Path source : mainSources()) {
      String code = withoutComments(Files.readString(source));
      for (String call : callArguments(code, "BusinessEventLogger.")) {
        assertThat(call)
            .as("%s logs a client address", source.getFileName())
            .doesNotContain("\"sourceAddress\"", "\"clientIp\"", "\"ipAddress\"", "\"remoteAddr\"",
                "\"source\"", "\"peer\"");
      }
      for (String call : callArguments(code, "meterRegistry.counter(")) {
        assertThat(call)
            .as("%s tags a metric with a client address", source.getFileName())
            .doesNotContain("\"sourceAddress\"", "\"clientIp\"", "\"ipAddress\"", "\"source\"");
      }
    }
  }

  @Test
  @DisplayName("the registration source bucket is hashed before it is persisted")
  void sourceBucketsArePersistedHashed() throws Exception {
    String repository = Files.readString(
        MAIN_SOURCES.resolve("registration/RegistrationRepository.java"));
    // withinCeiling hashes every dimension, so no abuse_counter row holds an address.
    assertThat(withoutComments(repository)).contains("sha256(dimension)");
  }

  private static List<String> callArguments(String code, String prefix) {
    List<String> calls = new java.util.ArrayList<>();
    int cursor = code.indexOf(prefix);
    while (cursor >= 0) {
      int end = code.indexOf(");", cursor);
      calls.add(end < 0 ? code.substring(cursor) : code.substring(cursor, end));
      cursor = code.indexOf(prefix, cursor + prefix.length());
    }
    return calls;
  }
}
