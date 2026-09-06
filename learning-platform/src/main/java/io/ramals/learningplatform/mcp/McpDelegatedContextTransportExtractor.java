package io.ramals.learningplatform.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * MCP-2 (M2-ADR-031, security-review fix): lifts the raw delegated learner-context credential out of
 * the HTTP request and into the exchange-scoped {@link McpTransportContext} every tool call handler
 * can read -- the official SDK's own supported hook for per-request transport metadata (wired via
 * {@code HttpServletStreamableServerTransportProvider.Builder#contextExtractor}), never a ThreadLocal
 * or other invented mechanism.
 *
 * <p><b>This class does not validate the token.</b> It only relocates it from a header to the
 * transport context; {@link io.ramals.learningplatform.mcp.authorization.McpCapabilityAuthorization#authorizeCapability}
 * still performs every signature/issuer/audience/expiry/structural check, exactly as before -- the
 * only thing that has moved is <em>where the raw token comes from</em>, never how it is checked.
 *
 * <p>Deliberately a dedicated HTTP header, never a JSON-RPC tool argument: a tool argument is
 * model-visible and model-selectable (MCP-3 will let an LLM choose tool arguments), and the delegated
 * learner-context credential is an authorization credential the model must never be responsible for
 * carrying, reproducing, or replaying. A header set by the calling workload (never by model-generated
 * tool-call content) is not part of the business/tool schema at all, so no model or tool-call
 * generation logic can ever see, choose, or fabricate it.
 */
public final class McpDelegatedContextTransportExtractor
    implements McpTransportContextExtractor<HttpServletRequest> {

  /** The dedicated transport-level header the {@code ramals-ai-workload} caller sets per request --
   * never a tool argument, never logged, never echoed into any tool result. */
  public static final String HEADER_NAME = "X-Ramals-Delegated-Context";

  /** The transport-context map key the raw token is stored under -- internal to the Java process;
   * never serialized to the client, the model, or any tool schema. Public only so {@code
   * io.ramals.learningplatform.mcp.resources} can read it back from {@code
   * McpSyncServerExchange.transportContext()}; the key itself never leaves this process. */
  public static final String TRANSPORT_CONTEXT_KEY = "delegatedContextToken";

  @Override
  public McpTransportContext extract(HttpServletRequest request) {
    String rawToken = request.getHeader(HEADER_NAME);
    if (rawToken == null || rawToken.isBlank()) {
      return McpTransportContext.EMPTY;
    }
    return McpTransportContext.create(Map.of(TRANSPORT_CONTEXT_KEY, rawToken));
  }
}
