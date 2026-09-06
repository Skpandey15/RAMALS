package io.ramals.learningplatform.mcp.resources;

import static org.mockito.Mockito.mock;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpLoggableSession;
import io.ramals.learningplatform.mcp.McpDelegatedContextTransportExtractor;
import java.util.Map;

/**
 * Test helper: builds a real {@link McpSyncServerExchange} carrying a delegated-context token in its
 * transport context -- the same shape {@link
 * io.ramals.learningplatform.mcp.McpDelegatedContextTransportExtractor} produces from the real HTTP
 * header, so tool handler tests exercise the exact same {@code exchange.transportContext()} read path
 * production code uses, never a tool argument.
 */
final class McpTestExchanges {

  private McpTestExchanges() {
  }

  /** An exchange whose transport context carries {@code token} (or none at all, if {@code token} is
   * {@code null}) -- mirrors a request with, or without, the delegated-context HTTP header set. */
  static McpSyncServerExchange withDelegatedContextToken(String token) {
    McpTransportContext transportContext = token == null
        ? McpTransportContext.EMPTY
        : McpTransportContext.create(
            Map.of(McpDelegatedContextTransportExtractor.TRANSPORT_CONTEXT_KEY, token));
    McpAsyncServerExchange asyncExchange = new McpAsyncServerExchange(
        "test-session", mock(McpLoggableSession.class), null, null, transportContext);
    return new McpSyncServerExchange(asyncExchange);
  }
}
