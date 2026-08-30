package io.ramals.learningplatform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Client-address derivation for the pre-authentication ceilings.
 *
 * <p>The property under test is that a caller who can reach the service directly cannot choose their
 * own rate-limit bucket. Everything else here is in service of that.
 */
class ClientAddressResolverTests {

  private static final List<String> PRIVATE_PROXIES =
      List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

  private static ClientAddressResolver resolver(List<String> trustedProxies) {
    TrustedProxyProperties properties = new TrustedProxyProperties();
    properties.setTrustedProxies(trustedProxies);
    return new ClientAddressResolver(properties);
  }

  private static HttpServletRequest request(String remoteAddr, String forwardedFor) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(remoteAddr);
    if (forwardedFor != null) {
      request.addHeader("X-Forwarded-For", forwardedFor);
    }
    return request;
  }

  // -------------------------------------------------------------------------------------------
  // The bypass this exists to close
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("an untrusted peer cannot choose its bucket by rotating X-Forwarded-For")
  void spoofedForwardedForFromAnUntrustedPeerIsIgnored() {
    ClientAddressResolver resolver = resolver(PRIVATE_PROXIES);
    // The attack: reach the backend directly, send a different XFF each time, get a fresh 30/5min
    // allowance per value. Every one of these must land on the same bucket.
    for (String spoofed : new String[] {"1.1.1.1", "1.1.1.2", "1.1.1.3", "8.8.8.8, 9.9.9.9"}) {
      assertThat(resolver.resolve(request("203.0.113.7", spoofed))).isEqualTo("203.0.113.7");
    }
  }

  @Test
  @DisplayName("different spoofed headers behind one untrusted peer share one bucket")
  void spoofedHeadersCollapseOntoOneBucket() {
    ClientAddressResolver resolver = resolver(PRIVATE_PROXIES);
    String first = resolver.resolve(request("203.0.113.7", "5.5.5.5"));
    String second = resolver.resolve(request("203.0.113.7", "6.6.6.6"));
    assertThat(first).isEqualTo(second);
  }

  @Test
  @DisplayName("a peer outside every configured CIDR is not trusted")
  void unconfiguredProxyIsNotTrusted() {
    ClientAddressResolver resolver = resolver(List.of("10.0.0.0/8"));
    assertThat(resolver.resolve(request("172.16.0.9", "1.2.3.4"))).isEqualTo("172.16.0.9");
  }

  @Test
  @DisplayName("with no trusted proxies configured, forwarding headers are never consulted")
  void emptyConfigurationTrustsNothing() {
    ClientAddressResolver resolver = resolver(List.of());
    assertThat(resolver.resolve(request("10.0.0.5", "1.2.3.4"))).isEqualTo("10.0.0.5");
  }

  // -------------------------------------------------------------------------------------------
  // Correct derivation through trusted hops
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("with no forwarding header the peer address is used")
  void noHeaderUsesRemoteAddr() {
    assertThat(resolver(PRIVATE_PROXIES).resolve(request("203.0.113.7", null)))
        .isEqualTo("203.0.113.7");
  }

  @Test
  @DisplayName("a trusted proxy with one hop yields the client address")
  void trustedProxySingleHop() {
    assertThat(resolver(PRIVATE_PROXIES).resolve(request("10.0.0.5", "203.0.113.9")))
        .isEqualTo("203.0.113.9");
  }

  @Test
  @DisplayName("through several trusted hops the first untrusted address is derived")
  void trustedProxyChainYieldsFirstUntrustedHop() {
    // client -> ALB -> ingress -> RAMALS. Both infrastructure hops are trusted, so the client is
    // the right-most entry that is not one of them.
    assertThat(resolver(PRIVATE_PROXIES)
        .resolve(request("10.0.0.5", "203.0.113.9, 10.1.2.3, 172.16.4.5")))
        .isEqualTo("203.0.113.9");
  }

  @Test
  @DisplayName("a client that spoofs extra hops before reaching a trusted proxy cannot hide")
  void spoofedPrefixBehindATrustedProxyDoesNotWin() {
    // The client wrote "1.1.1.1" itself; the proxy appended the address it actually saw. Reading
    // from the right finds the appended one, which is the only entry with known provenance.
    assertThat(resolver(PRIVATE_PROXIES).resolve(request("10.0.0.5", "1.1.1.1, 203.0.113.9")))
        .isEqualTo("203.0.113.9");
  }

  @Test
  @DisplayName("legitimate clients behind one trusted proxy stay distinguishable")
  void clientsBehindATrustedProxyRemainSeparate() {
    ClientAddressResolver resolver = resolver(PRIVATE_PROXIES);
    assertThat(resolver.resolve(request("10.0.0.5", "203.0.113.9")))
        .isNotEqualTo(resolver.resolve(request("10.0.0.5", "203.0.113.10")));
  }

  @Test
  @DisplayName("a chain of only trusted proxies falls back to the peer")
  void allTrustedChainFallsBackToPeer() {
    assertThat(resolver(PRIVATE_PROXIES).resolve(request("10.0.0.5", "10.1.1.1, 172.16.2.2")))
        .isEqualTo("10.0.0.5");
  }

  // -------------------------------------------------------------------------------------------
  // Malformed and hostile input
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("malformed and empty forwarding values fall back to the peer")
  void malformedValuesFallBackToPeer() {
    ClientAddressResolver resolver = resolver(PRIVATE_PROXIES);
    for (String header : new String[] {
        "", "   ", ",", ",,,", "not-an-address", "999.999.999.999", "<script>", "%00",
        "10.0.0.5, garbage", "::::::"}) {
      assertThat(resolver.resolve(request("10.0.0.5", header)))
          .as("header %s must fall back to the peer", header)
          .isEqualTo("10.0.0.5");
    }
  }

  @Test
  @DisplayName("a hostname in the chain is never resolved")
  void hostnamesAreRejectedRatherThanResolved() {
    // InetAddress.getByName resolves names, so accepting one would let a header drive a DNS lookup
    // per request against a name the caller picked.
    assertThat(resolver(PRIVATE_PROXIES).resolve(request("10.0.0.5", "attacker.example.com")))
        .isEqualTo("10.0.0.5");
  }

  @Test
  @DisplayName("an oversized chain is discarded rather than truncated")
  void oversizedChainIsDiscarded() {
    ClientAddressResolver resolver = resolver(PRIVATE_PROXIES);
    String longChain = String.join(", ", java.util.Collections.nCopies(64, "203.0.113.9"));
    assertThat(resolver.resolve(request("10.0.0.5", longChain))).isEqualTo("10.0.0.5");
  }

  @Test
  @DisplayName("an oversized header is discarded before it is split")
  void oversizedHeaderIsDiscarded() {
    TrustedProxyProperties properties = new TrustedProxyProperties();
    properties.setTrustedProxies(PRIVATE_PROXIES);
    properties.setMaxHeaderLength(32);
    ClientAddressResolver resolver = new ClientAddressResolver(properties);
    assertThat(resolver.resolve(request("10.0.0.5", "203.0.113.9".repeat(20))))
        .isEqualTo("10.0.0.5");
  }

  @Test
  @DisplayName("a hop ceiling of one still admits a single-hop chain")
  void hopCeilingIsInclusive() {
    TrustedProxyProperties properties = new TrustedProxyProperties();
    properties.setTrustedProxies(PRIVATE_PROXIES);
    properties.setMaxHops(1);
    ClientAddressResolver resolver = new ClientAddressResolver(properties);
    assertThat(resolver.resolve(request("10.0.0.5", "203.0.113.9"))).isEqualTo("203.0.113.9");
    assertThat(resolver.resolve(request("10.0.0.5", "203.0.113.9, 10.1.1.1")))
        .isEqualTo("10.0.0.5");
  }

  @Test
  @DisplayName("a bucket is never derived from an unbounded set of malformed values")
  void malformedValuesDoNotMintBuckets() {
    ClientAddressResolver resolver = resolver(PRIVATE_PROXIES);
    // Whatever the caller writes, a malformed chain resolves to the peer, so the number of distinct
    // buckets stays bounded by the number of real peers.
    java.util.Set<String> buckets = new java.util.HashSet<>();
    for (int index = 0; index < 500; index++) {
      buckets.add(resolver.resolve(request("10.0.0.5", "junk-" + index)));
    }
    assertThat(buckets).containsExactly("10.0.0.5");
  }

  // -------------------------------------------------------------------------------------------
  // Address families and CIDR matching
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("IPv6 peers and chains are handled")
  void ipv6IsSupported() {
    ClientAddressResolver resolver = resolver(List.of("2001:db8::/32"));
    // The client must sit outside the trusted block; an address inside it is another proxy hop.
    assertThat(resolver.resolve(request("2001:db8::1", "2001:dead:beef::9")))
        .isEqualTo("2001:dead:beef:0:0:0:0:9");
    // An IPv6 peer outside the trusted block keeps its own address.
    assertThat(resolver.resolve(request("2001:dead::1", "2001:db8::9")))
        .isEqualTo("2001:dead:0:0:0:0:0:1");
    // And a chain entirely inside the trusted /32 exhausts and falls back to the peer.
    assertThat(resolver.resolve(request("2001:db8::1", "2001:db8:abcd::9")))
        .isEqualTo("2001:db8:0:0:0:0:0:1");
  }

  @Test
  @DisplayName("an IPv4-mapped IPv6 address is the same host as its IPv4 spelling")
  void ipv4MappedAddressesNormalize() {
    ClientAddressResolver resolver = resolver(List.of("10.0.0.0/8"));
    // Same host written two ways must key one bucket, and must match the CIDR either way.
    assertThat(resolver.resolve(request("::ffff:203.0.113.7", null))).isEqualTo("203.0.113.7");
    assertThat(resolver.resolve(request("::ffff:10.0.0.5", "203.0.113.9")))
        .isEqualTo("203.0.113.9");
  }

  @Test
  @DisplayName("a port on the peer or a hop is stripped")
  void portsAreStripped() {
    ClientAddressResolver resolver = resolver(PRIVATE_PROXIES);
    assertThat(resolver.resolve(request("203.0.113.7:51234", null))).isEqualTo("203.0.113.7");
    assertThat(resolver.resolve(request("10.0.0.5", "203.0.113.9:443"))).isEqualTo("203.0.113.9");
    assertThat(resolver.resolve(request("10.0.0.5", "[2001:db8::9]:443")))
        .isEqualTo("2001:db8:0:0:0:0:0:9");
  }

  @Test
  @DisplayName("CIDR boundaries are matched on bits, not on string prefixes")
  void cidrMatchingIsBitwise() {
    ClientAddressResolver resolver = resolver(List.of("10.1.2.0/24"));
    assertThat(resolver.resolve(request("10.1.2.255", "203.0.113.9"))).isEqualTo("203.0.113.9");
    // One address outside the /24 must not be trusted, even though the text prefix looks close.
    assertThat(resolver.resolve(request("10.1.3.0", "203.0.113.9"))).isEqualTo("10.1.3.0");
  }

  @Test
  @DisplayName("a single-address entry without a prefix trusts exactly that address")
  void bareAddressIsAHostRoute() {
    ClientAddressResolver resolver = resolver(List.of("10.0.0.5"));
    assertThat(resolver.resolve(request("10.0.0.5", "203.0.113.9"))).isEqualTo("203.0.113.9");
    assertThat(resolver.resolve(request("10.0.0.6", "203.0.113.9"))).isEqualTo("10.0.0.6");
  }

  @Test
  @DisplayName("an address family mismatch never matches a CIDR")
  void familiesDoNotCrossMatch() {
    ClientAddressResolver resolver = resolver(List.of("10.0.0.0/8"));
    assertThat(resolver.resolve(request("2001:db8::1", "203.0.113.9")))
        .isEqualTo("2001:db8:0:0:0:0:0:1");
  }

  @Test
  @DisplayName("invalid trusted-proxy configuration fails at startup, not at request time")
  void invalidConfigurationFailsFast() {
    // Silently dropping a malformed entry would mean believing a proxy is trusted when it is not.
    for (String entry : new String[] {"not-a-cidr", "10.0.0.0/64", "10.0.0.0/abc", "10.0.0.0/-1"}) {
      assertThatThrownBy(() -> resolver(List.of(entry)))
          .as("entry %s must be rejected", entry)
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // -------------------------------------------------------------------------------------------
  // Headers we deliberately do not honour
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("X-Real-IP and Forwarded are ignored even from a trusted proxy")
  void otherForwardingHeadersAreNotHonoured() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.5");
    request.addHeader("X-Real-IP", "1.2.3.4");
    request.addHeader("Forwarded", "for=1.2.3.4");
    // Only X-Forwarded-For is set by the proxies actually in front of this service. An unused
    // header that is nonetheless trusted is a second way in.
    assertThat(resolver(PRIVATE_PROXIES).resolve(request)).isEqualTo("10.0.0.5");
  }
}
