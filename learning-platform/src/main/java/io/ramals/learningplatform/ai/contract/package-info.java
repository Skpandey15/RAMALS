/**
 * Java view of the internal AI contract.
 *
 * <p>These records are hand-written and <em>validated</em> against
 * {@code contracts/ai-internal.openapi.yaml} rather than generated from it (M1-ADR-002). The Python
 * side is generated; parity is enforced by the golden fixtures in {@code contracts/golden/}, which
 * both languages deserialize and re-serialize.
 *
 * <p>That makes the fixtures the real guarantee. If they are ever weakened, the reason this
 * arrangement is safe disappears with them.
 *
 * <p>Nothing here is authoritative. Every value that crosses this boundary is a proposal until
 * Spring's deterministic policy has accepted it.
 */
package io.ramals.learningplatform.ai.contract;
