package io.ramals.learningplatform.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.ai.DelegatedAiContextMinter;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextIssuer;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * MCP-3.1 (M2-ADR-031): {@code ramals.mcp.enabled=true} with no delegated-context signing key
 * configured must start the application successfully -- exactly the same "absent means safely
 * off, not a startup failure" discipline {@link McpSecurityConfigTests} already proves for {@code
 * DelegatedLearnerContextSigningKeys} -- and must leave {@link DelegatedAiContextMinter} with no
 * issuer to call, never a broader/default grant standing in for a real one.
 *
 * <p>This does not rely on a comment's claim that a null-returning {@code @Bean} method is "not
 * published" -- it asserts the actual, observed Spring behavior: the context loading at all (this
 * test class running) proves no {@link IllegalStateException} escaped {@code
 * McpServerConfig#delegatedLearnerContextIssuer} at startup, and the autowired {@link Optional}
 * being empty proves Spring genuinely registered no bean for a null return, rather than, say,
 * registering one that would NPE on first use.
 */
@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:mcp-server-config;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false",
    "ramals.mcp.enabled=true"
})
class McpServerConfigTests {

  @Autowired
  Optional<DelegatedLearnerContextIssuer> delegatedLearnerContextIssuer;

  @Autowired
  DelegatedAiContextMinter delegatedAiContextMinter;

  @Test
  void applicationContextStartsWithMcpEnabledAndNoDelegatedContextSigningKeyConfigured() {
    // The context having loaded at all (this test executing) is half the proof: a null-returning
    // @Bean method for DelegatedLearnerContextIssuer did not fail application startup the moment
    // MCP was enabled but its signing key was not yet configured.
    assertThat(delegatedLearnerContextIssuer).isEmpty();
  }

  @Test
  void theMinterMintsNothingWhenNoIssuerBeanWasPublished() {
    var context = delegatedAiContextMinter.mint(
        "interaction-1", "learner-ref-1", () -> "KAFKA", Set.of("mastery.current"));

    assertThat(context.token()).isEmpty();
  }
}
