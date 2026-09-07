package io.ramals.learningplatform.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextIssuer;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextSigningKeys;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidator;
import io.ramals.learningplatform.mcp.authorization.McpCapabilityAuthorization;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP-1 (M2-ADR-031): the Java-hosted MCP protocol/transport foundation, over Streamable HTTP.
 *
 * <p>Entirely off by default ({@code ramals.mcp.enabled=false}) -- no bean in this class exists
 * unless explicitly enabled, matching {@code AiClientConfiguration}'s own "unconfigured means
 * absent, not a startup failure" discipline.
 *
 * <p>No Spring AI dependency of any kind is used here. {@link HttpServletStreamableServerTransportProvider}
 * is a plain {@code jakarta.servlet.http.HttpServlet} shipped inside the official MCP Java SDK's own
 * {@code mcp-core} module (confirmed directly against the resolved jar before writing this class) --
 * it is registered exactly like any other servlet this application serves, through an ordinary
 * {@link ServletRegistrationBean}, needing no Spring MVC {@code RouterFunction} and no
 * {@code org.springframework.ai} transport module.
 *
 * <p><b>MCP-2</b>: exactly five read-only, learner-scoped business capabilities are registered --
 * {@link McpCapabilityRegistry#mcp2()} -- as explicit MCP tools (see {@code
 * io.ramals.learningplatform.mcp.resources}), each its own {@code @Bean}, collected here via ordinary
 * Spring DI (a {@code List<SyncToolSpecification>} parameter), never reflection or classpath
 * scanning: adding a sixth capability means adding a sixth {@code @Bean} and a sixth registry entry,
 * in the same reviewed change, never automatically.
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnProperty(prefix = "ramals.mcp", name = "enabled", havingValue = "true")
public class McpServerConfig {

  @Bean
  McpCapabilityRegistry mcpCapabilityRegistry() {
    return McpCapabilityRegistry.mcp2();
  }

  /**
   * MCP-2: now wired against real key material, since real learner-scoped capabilities exist to call
   * it. Still harmless without configuration -- {@link DelegatedLearnerContextSigningKeys#allSigningKeys}
   * returns an empty map when nothing is configured (never throws for an empty map), so an
   * unconfigured deployment simply fails every delegated-context validation with {@code
   * UNKNOWN_KEY_ID} rather than failing to start.
   */
  @Bean
  DelegatedLearnerContextValidator delegatedLearnerContextValidator(
      McpProperties properties, DelegatedLearnerContextSigningKeys signingKeys) {
    McpProperties.DelegatedContext delegatedContext = properties.getDelegatedContext();
    return new DelegatedLearnerContextValidator(
        delegatedContext.getIssuer(), delegatedContext.getAudience(),
        signingKeys.allSigningKeys(), Clock.systemUTC());
  }

  /**
   * MCP-2: the shared capability/domain/learner-scope authorization layer every tool handler in
   * {@code io.ramals.learningplatform.mcp.resources} routes through. Registered here, not as its own
   * {@code @Component}, so it only exists -- and only ever requires {@link McpCapabilityRegistry} to
   * exist -- while MCP itself is enabled.
   */
  @Bean
  McpCapabilityAuthorization mcpCapabilityAuthorization(
      McpCapabilityRegistry registry, DelegatedLearnerContextValidator validator) {
    return new McpCapabilityAuthorization(registry, validator);
  }

  /**
   * Registered and harmless without configuration, exactly like {@code
   * EnvironmentResultEncryptionKeyProvider}: with no delegated-context key configured, every method
   * on this bean throws on first call rather than the platform starting with a default key.
   * {@link #delegatedLearnerContextValidator} (MCP-2) and {@link #delegatedLearnerContextIssuer}
   * (MCP-3.1) are this bean's only two callers, and neither is affected by an unconfigured key at
   * startup: the validator's own {@link DelegatedLearnerContextSigningKeys#allSigningKeys()} call
   * never throws for an empty key map, and the issuer bean method checks for a configured active key
   * itself before ever calling into this class.
   */
  @Bean
  DelegatedLearnerContextSigningKeys delegatedLearnerContextSigningKeys(McpProperties properties) {
    return new DelegatedLearnerContextSigningKeys(properties.getDelegatedContext());
  }

  /**
   * MCP-3.1: the outbound Java→ramals-ai side's counterpart to {@link #delegatedLearnerContextValidator}
   * above -- mints rather than verifies, but is issued from the exact same {@link
   * McpProperties.DelegatedContext} configuration and the exact same signing key material, never a
   * second key or a second token format.
   *
   * <p>Registers no bean at all (returns {@code null}, which Spring simply does not publish) when no
   * delegated-context signing key is configured yet -- {@code ramals.mcp.enabled=true} alone is not
   * enough. Unlike {@link #delegatedLearnerContextValidator}, which stays harmlessly unconfigured
   * because {@link DelegatedLearnerContextSigningKeys#allSigningKeys()} never throws for an empty
   * key map, {@link DelegatedLearnerContextIssuer}'s constructor needs one concrete active key
   * eagerly, at construction time -- calling {@link DelegatedLearnerContextSigningKeys#activeKeyId()}
   * here unconditionally would fail application startup the moment MCP is enabled but not yet fully
   * configured, which is exactly the failure mode this codebase's "absent means safely off, not a
   * startup failure" discipline (see {@code AiClientConfiguration}, {@code McpProperties}) forbids.
   * {@link DelegatedAiContextMinter} already treats a missing bean here as "mint nothing" -- never a
   * broader or default token.
   */
  @Bean
  DelegatedLearnerContextIssuer delegatedLearnerContextIssuer(
      McpProperties properties, DelegatedLearnerContextSigningKeys signingKeys) {
    McpProperties.DelegatedContext delegatedContext = properties.getDelegatedContext();
    if (delegatedContext.getActiveKeyId() == null || delegatedContext.getActiveKeyId().isBlank()) {
      return null;
    }
    return new DelegatedLearnerContextIssuer(
        delegatedContext.getIssuer(),
        delegatedContext.getAudience(),
        Duration.ofSeconds(delegatedContext.getTtlSeconds()),
        signingKeys.activeKeyId(),
        signingKeys.activeSigningKey(),
        Clock.systemUTC());
  }

  @Bean
  McpJsonMapper mcpJsonMapper() {
    // The same Jackson 3 (tools.jackson) generation this application already uses everywhere else
    // (e.g. AssessmentRepository's own JsonMapper) -- not a second JSON stack.
    return new JacksonMcpJsonMapper(JsonMapper.builder().build());
  }

  @Bean
  HttpServletStreamableServerTransportProvider mcpTransportProvider(
      McpJsonMapper jsonMapper, McpProperties properties) {
    return HttpServletStreamableServerTransportProvider.builder()
        .jsonMapper(jsonMapper)
        .mcpEndpoint(properties.getEndpoint())
        // Security-review fix: lifts the delegated learner-context credential out of a dedicated
        // HTTP header (never a JSON-RPC tool argument) into the exchange-scoped transport context
        // every tool handler reads from -- the SDK's own supported per-request metadata hook, not an
        // invented ThreadLocal. See McpDelegatedContextTransportExtractor's own javadoc.
        .contextExtractor(new McpDelegatedContextTransportExtractor())
        .build();
  }

  /**
   * Constructing this server is what wires the transport provider's session handling into effect
   * (the provider's own session factory is set during {@code McpServer.sync(...).build()}), so this
   * bean must exist as a live singleton even though nothing else in MCP-2 calls a method on it
   * directly.
   *
   * <p>{@code tools} is every {@code SyncToolSpecification} bean Spring finds -- exactly the five
   * {@code @Bean} methods in {@code io.ramals.learningplatform.mcp.resources}, collected by ordinary
   * dependency injection, not a scan of arbitrary classes: only a class explicitly declared as
   * producing this exact bean type is ever included.
   */
  @Bean
  McpSyncServer mcpSyncServer(
      HttpServletStreamableServerTransportProvider transportProvider,
      List<SyncToolSpecification> tools) {
    return McpServer.sync(transportProvider)
        .serverInfo(new McpSchema.Implementation("ramals-learning-platform", "1.0.0"))
        // MCP-2 offers tools only -- no resource, no prompt. Widening the capability set beyond
        // tools is exactly the kind of change that must happen alongside a McpCapabilityRegistry
        // change, in the same reviewed commit -- never independently. `false` (no listChanged
        // support): the tool set is fixed at startup by explicit @Bean registration, never mutated
        // at runtime, so there is never a list-changed event to notify a client about.
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .tools(tools)
        // Validates every call's arguments against each tool's own declared inputSchema (including
        // additionalProperties: false) before a handler ever runs -- a first, protocol-level line of
        // defense against a learner-identifying or otherwise unexpected parameter, ahead of every
        // handler's own explicit checks.
        .validateToolInputs(true)
        .build();
  }

  @Bean
  ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServletRegistration(
      HttpServletStreamableServerTransportProvider transportProvider,
      // Declared as a construction-order dependency only: Spring must build the McpSyncServer (which
      // performs the session-factory wiring above) before this servlet is registered and able to
      // receive traffic.
      McpSyncServer mcpSyncServer,
      McpProperties properties) {
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
        new ServletRegistrationBean<>(transportProvider, properties.getEndpoint());
    registration.setName("mcpTransportServlet");
    registration.setLoadOnStartup(1);
    return registration;
  }
}
