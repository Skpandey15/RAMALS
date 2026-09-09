package io.ramals.learningplatform.diagnosticassessment;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A parsed advisory diagnostic-probe proposal (M2-ADR-032, contract
 * {@code contracts/mvp2/diagnostic-probe-proposal.v1.schema.json}).
 *
 * <p><b>Distinct from {@link DiagnosticProbeProposal}'s sibling {@link DiagnosticAssessmentProposal}
 * on purpose.</b> That one is a skill-by-skill verdict about the learner. This one asserts nothing
 * about the learner: it names one bounded next evidence-acquisition probe candidate, referencing
 * only governed evidence and one already-authored misconception. The two share no code and no gate.
 *
 * <p>Parsed from the agent's payload rather than bound directly, because the payload is untrusted
 * model output: a field that will not parse, an unknown field, or a forbidden field is a rejection
 * with a stable reason code -- not an exception thrown out of a deserializer, and never silently
 * ignored (M2-ADR-032 Context finding 1, and 22: "reject unknown fields").
 *
 * <p>Every correlation identifier ({@code proposalId}, {@code requestId}, {@code agentRunId}) is
 * bound by the caller from what it actually sent. {@code interactionId} and {@code domain} are read
 * from the payload only so the gate can cross-check them against the interaction's own authoritative
 * values; they can never select or widen scope (M2-ADR-032 4.3/4.4, 21).
 */
public record DiagnosticProbeProposal(
    String contractVersion,
    String proposalId,
    String requestId,
    String agentRunId,
    String interactionId,
    String domain,
    UUID targetMisconceptionId,
    TargetNode targetNode,
    ProbeIntent probeIntent,
    UUID candidateProbeRef,
    List<String> evidenceRefs,
    String rationale) {

  /** The wire contract version this parser accepts. Anything else fails closed. */
  public static final String CONTRACT_VERSION = "1.0";

  /** The only proposal type this contract carries (M2-ADR-032 22 discriminator). */
  public static final String PROPOSAL_TYPE = "DIAGNOSTIC_PROBE_CANDIDATE";

  static final int MAX_ID = 64;
  static final int MAX_DOMAIN = 64;
  static final int MAX_RATIONALE = 1000;
  static final int MAX_EVIDENCE_REFS = 64;

  /**
   * Exactly the keys the v1 contract declares. Anything outside this set is rejected rather than
   * ignored -- the specific failure M2-ADR-032's Context section records about the older parser.
   */
  private static final Set<String> ALLOWED_KEYS =
      Set.of(
          "contractVersion",
          "proposalType",
          "proposalId",
          "requestId",
          "agentRunId",
          "interactionId",
          "domain",
          "targetMisconceptionId",
          "targetNode",
          "probeIntent",
          "candidateProbeRef",
          "evidenceRefs",
          "rationale");

  /**
   * Field names that must never appear on this proposal type, checked explicitly even though {@link
   * #ALLOWED_KEYS} would already reject them: a bare {@code confidence}/{@code probability}/{@code
   * rank} is the specific overreach M2-ADR-032 3/22 forbid, and a dedicated reason code makes that
   * rejection legible in an audit rather than a generic "unknown field".
   */
  private static final Set<String> FORBIDDEN_KEYS =
      Set.of(
          "confidence",
          "probability",
          "likelihood",
          "rank",
          "ranking",
          "score",
          "diagnosis",
          "classification",
          "rootCause",
          "learnerId",
          "learnerRef");

  public DiagnosticProbeProposal {
    evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
  }

  /** The single ontology entity the target misconception is attached to (M2-ADR-026 exclusive arc). */
  public record TargetNode(Kind kind, UUID id) {
    public enum Kind {
      LEARNING_OBJECTIVE,
      CONCEPT,
      SUB_CONCEPT
    }
  }

  /**
   * The bounded, closed set of advisory intents. Neither value asserts a diagnosis, a probability,
   * or that a probe is eligible to run.
   */
  public enum ProbeIntent {
    COLLECT_ADDITIONAL_MISCONCEPTION_EVIDENCE,
    DISCRIMINATE_BETWEEN_EVIDENCE_STATES
  }

  /** Raised when a payload cannot be read as this contract at all. Carries a stable reason code. */
  public static final class MalformedProbeProposalException extends RuntimeException {
    private final transient String reasonCode;

    MalformedProbeProposalException(String reasonCode, String message) {
      super(message);
      this.reasonCode = reasonCode;
    }

    public String reasonCode() {
      return reasonCode;
    }
  }

  /**
   * Reads an agent payload into this contract, or refuses it.
   *
   * <p>Every bound and every key is checked here rather than trusted from the Python side. The agent
   * validates locally too; this side cannot verify that it did, and a proposal reaching this method
   * has crossed a network from a service that could be a different version.
   */
  public static DiagnosticProbeProposal parse(
      Map<String, Object> payload, String proposalId, String requestId, String agentRunId) {
    if (payload == null || payload.isEmpty()) {
      throw new MalformedProbeProposalException("PROPOSAL_PAYLOAD_ABSENT", "no proposal payload");
    }
    for (String key : payload.keySet()) {
      if (FORBIDDEN_KEYS.contains(key)) {
        throw new MalformedProbeProposalException(
            "PROPOSAL_FORBIDDEN_FIELD",
            "field '" + key + "' is not permitted on an advisory diagnostic-probe proposal");
      }
      if (!ALLOWED_KEYS.contains(key)) {
        throw new MalformedProbeProposalException(
            "PROPOSAL_UNKNOWN_FIELD", "unknown field '" + key + "'");
      }
    }

    requireConst(payload.get("contractVersion"), CONTRACT_VERSION, "PROPOSAL_CONTRACT_VERSION_INVALID");
    requireConst(payload.get("proposalType"), PROPOSAL_TYPE, "PROPOSAL_TYPE_INVALID");

    String interactionId = bounded(payload.get("interactionId"), MAX_ID, "PROPOSAL_INTERACTION_INVALID");
    String domain = bounded(payload.get("domain"), MAX_DOMAIN, "PROPOSAL_DOMAIN_INVALID");
    UUID targetMisconceptionId =
        uuid(payload.get("targetMisconceptionId"), "PROPOSAL_TARGET_MISCONCEPTION_INVALID");
    TargetNode targetNode = parseTargetNode(payload.get("targetNode"));
    ProbeIntent probeIntent = parseIntent(payload.get("probeIntent"));
    UUID candidateProbeRef = optionalUuid(payload.get("candidateProbeRef"));
    List<String> evidenceRefs = parseEvidenceRefs(payload.get("evidenceRefs"));
    String rationale = bounded(payload.get("rationale"), MAX_RATIONALE, "PROPOSAL_RATIONALE_INVALID");

    return new DiagnosticProbeProposal(
        CONTRACT_VERSION,
        proposalId,
        requestId,
        agentRunId,
        interactionId,
        domain,
        targetMisconceptionId,
        targetNode,
        probeIntent,
        candidateProbeRef,
        evidenceRefs,
        rationale);
  }

  private static TargetNode parseTargetNode(Object raw) {
    if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
      throw new MalformedProbeProposalException(
          "PROPOSAL_TARGET_NODE_INVALID", "targetNode must be an object");
    }
    for (Object key : map.keySet()) {
      if (!"kind".equals(key) && !"id".equals(key)) {
        throw new MalformedProbeProposalException(
            "PROPOSAL_TARGET_NODE_INVALID", "unknown targetNode field '" + key + "'");
      }
    }
    TargetNode.Kind kind;
    try {
      kind = TargetNode.Kind.valueOf(String.valueOf(map.get("kind")));
    } catch (IllegalArgumentException | NullPointerException unknown) {
      throw new MalformedProbeProposalException(
          "PROPOSAL_TARGET_NODE_INVALID", "unrecognised targetNode.kind");
    }
    UUID id = uuid(map.get("id"), "PROPOSAL_TARGET_NODE_INVALID");
    return new TargetNode(kind, id);
  }

  private static ProbeIntent parseIntent(Object raw) {
    try {
      return ProbeIntent.valueOf(String.valueOf(raw));
    } catch (IllegalArgumentException | NullPointerException unknown) {
      throw new MalformedProbeProposalException(
          "PROPOSAL_PROBE_INTENT_UNKNOWN", "unrecognised probeIntent");
    }
  }

  private static List<String> parseEvidenceRefs(Object raw) {
    if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > MAX_EVIDENCE_REFS) {
      throw new MalformedProbeProposalException(
          "PROPOSAL_EVIDENCE_REFS_INVALID", "evidenceRefs must be a bounded, non-empty array");
    }
    java.util.LinkedHashSet<String> refs = new java.util.LinkedHashSet<>();
    for (Object entry : list) {
      String ref = bounded(entry, MAX_ID, "PROPOSAL_EVIDENCE_REFS_INVALID");
      if (!refs.add(ref)) {
        throw new MalformedProbeProposalException(
            "PROPOSAL_EVIDENCE_REFS_INVALID", "evidenceRefs must be unique");
      }
    }
    return List.copyOf(refs);
  }

  private static void requireConst(Object raw, String expected, String reasonCode) {
    if (!(raw instanceof String value) || !expected.equals(value)) {
      throw new MalformedProbeProposalException(reasonCode, "expected '" + expected + "'");
    }
  }

  private static UUID uuid(Object raw, String reasonCode) {
    if (!(raw instanceof String value) || value.isBlank()) {
      throw new MalformedProbeProposalException(reasonCode, "a UUID string is required");
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException notAUuid) {
      throw new MalformedProbeProposalException(reasonCode, "not a UUID");
    }
  }

  private static UUID optionalUuid(Object raw) {
    if (raw == null) {
      return null;
    }
    return uuid(raw, "PROPOSAL_CANDIDATE_PROBE_REF_INVALID");
  }

  private static String bounded(Object raw, int maxLength, String reasonCode) {
    if (!(raw instanceof String value) || value.isBlank() || value.length() > maxLength) {
      throw new MalformedProbeProposalException(reasonCode, "a bounded, non-blank string is required");
    }
    return value;
  }
}
