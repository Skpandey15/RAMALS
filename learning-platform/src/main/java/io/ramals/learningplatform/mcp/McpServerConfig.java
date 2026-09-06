package io.ramals.learningplatform.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextSigningKeys;
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
 * <p><b>Zero business capabilities are registered.</b> The {@link McpSyncServer} built here declares
 * no tools, no resources, no prompts -- {@code ServerCapabilities.builder().build()}'s own default is
 * "none of the above" -- and {@link McpCapabilityRegistry#empty()} is the only registry this PR ever
 * constructs. A future capability is added by changing this class and its registry together, in a
 * reviewed change, never by annotation scanning.
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnProperty(prefix = "ramals.mcp", name = "enabled", havingValue = "true")
public class McpServerConfig {

  @Bean
  McpCapabilityRegistry mcpCapabilityRegistry() {
    return McpCapabilityRegistry.empty();
  }

  /**
   * Registered and harmless without configuration, exactly like {@code
   * EnvironmentResultEncryptionKeyProvider}: with no delegated-context key configured, every method
   * on this bean throws on first call rather than the platform starting with a default key. Nothing
   * calls it yet -- MCP-1 wires no {@code DelegatedLearnerContextIssuer} or {@code
   * DelegatedLearnerContextValidator} bean, since no learner-scoped capability exists to use them --
   * so an unconfigured or disabled deployment never sees this bean's validation at all.
   */
  @Bean
  DelegatedLearnerContextSigningKeys delegatedLearnerContextSigningKeys(McpProperties properties) {
    return new DelegatedLearnerContextSigningKeys(properties.getDelegatedContext());
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
        .build();
  }

  /**
   * Constructing this server is what wires the transport provider's session handling into effect
   * (the provider's own session factory is set during {@code McpServer.sync(...).build()}), so this
   * bean must exist as a live singleton even though nothing else in MCP-1 calls a method on it
   * directly.
   */
  @Bean
  McpSyncServer mcpSyncServer(HttpServletStreamableServerTransportProvider transportProvider) {
    return McpServer.sync(transportProvider)
        .serverInfo(new McpSchema.Implementation("ramals-learning-platform", "1.0.0"))
        // No capability declared: MCP-1 offers no tool, no resource, no prompt. Widening any of
        // these is exactly the kind of change that must happen alongside a McpCapabilityRegistry
        // change, in the same reviewed commit -- never independently.
        .capabilities(McpSchema.ServerCapabilities.builder().build())
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
