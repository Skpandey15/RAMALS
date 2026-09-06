package io.ramals.learningplatform.mcp.resources;

import java.math.BigDecimal;

/**
 * MCP-2 wire shape for one skill's latest mastery -- shared by {@code mastery.current}'s own result
 * and by {@code diagnostics.current-domain-report}'s embedded mastery context, mirroring {@code
 * MasteryMapEntry} (the authoritative read model), never the REST {@code MasteryMapResponse.Skill}.
 * Read-only: no field or method here can express a mutation.
 */
public record McpMasterySkill(
    String skillCode,
    BigDecimal masteryScore,
    BigDecimal evidenceConfidence,
    String masteryStatus,
    int aggregateVersion) {
}
