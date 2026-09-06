package io.ramals.learningplatform.mcp.resources;

import java.util.List;

/**
 * MCP-2 wire shape for {@code mastery.current} -- the authoritative current mastery map for one
 * learner/domain/curriculum-version, read verbatim from {@code MasteryMapService}. Read-only: no
 * mutation method, no progression-eligibility field (MCP-2 does not expose progression eligibility --
 * left out per M2-ADR-031's own "if uncertain, leave it out" instruction, since no prior MCP
 * architecture decision authorized it and {@code MasteryMapEntry} itself carries no such field).
 */
public record McpMasteryReport(String domainCode, String versionCode, List<McpMasterySkill> skills) {
}
