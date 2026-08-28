package io.ramals.learningplatform.execution.contractb;

import io.ramals.learningplatform.diagnosticassessment.DiagnosticAssessmentProposal;
import io.ramals.learningplatform.execution.crypto.ResultEncryptionKeyUnavailableException;
import io.ramals.learningplatform.execution.crypto.ResultEnvelopeCodec;
import io.ramals.learningplatform.execution.crypto.ResultEnvelopeCorruptException;
import io.ramals.learningplatform.execution.crypto.SealedResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes and reads the one table in this schema permitted to contain model output.
 *
 * <p>The contract is narrow on purpose: a normalized proposal goes in as plaintext and never comes
 * back out of this class except to a caller that asked for it by request identity. Everything
 * between — validation, canonicalisation, digest, sealing — happens here so there is a single place
 * where the sequence can be checked, and no way for a caller to reach the column directly.
 *
 * <p><strong>Validation precedes encryption</strong>, which is M2-ADR-018 §10's fourth row and the
 * reason the order matters: <em>"the prohibition on reasoning content is enforced here, and a
 * failure means the invariant would have been broken."</em> Encrypting first and validating second
 * would produce a ciphertext of a document nobody had checked.
 *
 * <p>Validation is by <strong>re-serialisation from the parsed contract</strong>, not by filtering
 * the caller's document. This is the {@code V023} discipline — <em>"there is nothing to redact
 * because there is nowhere to put it"</em> — applied to a payload rather than a table: a
 * {@code thinking} block, a raw provider body or any other field outside
 * {@code diagnostic-proposal.v1} is not stripped, it simply has nowhere to go, because what gets
 * sealed is rebuilt from the record's own fields. A filter can be incomplete. A record with no such
 * component cannot carry one.
 *
 * <p><strong>Logging.</strong> This class logs, and every statement carries identity only — request
 * id, key id, digest, byte counts. The result, a fragment of it and its length never appear, on
 * success or on any failure path (M2-ADR-018 §10). The codec beneath it takes the stronger route of
 * having no logger at all; that is not available to a caller which genuinely needs to report what
 * happened, so the obligation is discharged here by what is said rather than by silence, and
 * asserted by test.
 */
@Component
public class ContractBResultStore {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContractBResultStore.class);

  /** The committed schema the stored document is validated against, recorded on the row. */
  public static final String RESULT_SCHEMA = "diagnostic-proposal.v1";

  /**
   * Idempotent by construction.
   *
   * <p>{@code ON CONFLICT DO NOTHING} rather than a check-then-insert, because the recovery this
   * exists for is a race: a process dies after committing the ciphertext and before marking the
   * execution terminal, so the replacement retrieves the same result and stores it again. Without
   * this clause that second write is a primary-key violation and recovery becomes a crash loop --
   * found by the K8 crash qualification, not by review.
   *
   * <p>Silently keeping the first row is the correct resolution and not merely the convenient one.
   * A result is immutable once written (M2-ADR-018 §3), so the existing row is authoritative by
   * definition; overwriting it would be the operation the whole design forbids.
   */
  private static final String INSERT = """
      INSERT INTO core.ai_execution_result
        (request_id, provider_execution_id, normalized_result, encryption_key_id,
         result_digest, result_schema, stored_at, purge_after)
      VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 days')
      ON CONFLICT (request_id) DO NOTHING
      """;

  private static final String SELECT = """
      SELECT normalized_result, encryption_key_id, result_digest
        FROM core.ai_execution_result
       WHERE request_id = ?
      """;

  private final JdbcTemplate jdbc;
  private final ResultEnvelopeCodec codec;
  private final ObjectMapper json;

  public ContractBResultStore(JdbcTemplate jdbc, ResultEnvelopeCodec codec, ObjectMapper json) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.json = json;
  }

  /**
   * Validates, seals and stores one normalized result.
   *
   * <p>Both failure modes leave nothing behind. A document that will not parse is refused before a
   * cipher is constructed; a key that will not resolve is refused before a row is built. There is no
   * ordering of these steps that writes plaintext, and no branch that stores the result
   * unencrypted "temporarily" (M2-ADR-018 §10).
   *
   * @param requestId the durable request identity — also the AAD the envelope is bound to, so a
   *     ciphertext written against one request cannot be read as another's
   * @param normalizedResultJson the agent's normalized proposal, as received
   * @return the digest and key id written, for the caller's provenance record
   * @throws ContractBResultRejectedException when the document is not a valid
   *     {@code diagnostic-proposal.v1}
   * @throws ResultEncryptionKeyUnavailableException when no usable key exists — the execution stays
   *     recoverable while the provider still holds the result
   */
  public StoredResult store(String requestId, String providerExecutionId,
      String normalizedResultJson) {
    requireIdentity(requestId, "a request identity is required to store a result");
    requireIdentity(providerExecutionId, "a provider execution identity is required");

    // 1. Validate against the committed schema, and rebuild from what parsed. Anything the contract
    //    does not describe does not survive this line.
    byte[] canonical = canonicalise(requestId, normalizedResultJson);
    String digest = sha256(canonical);

    // 2. Seal. Only now does the plaintext exist as bytes anywhere near a write.
    SealedResult sealed;
    try {
      sealed = codec.seal(canonical, requestId);
    } catch (ResultEncryptionKeyUnavailableException unavailable) {
      LOGGER.error("contract B result refused: no usable encryption key [requestId={}, keyId={}]",
          requestId, unavailable.keyId());
      throw unavailable;
    }

    int inserted = jdbc.update(INSERT, requestId, providerExecutionId, sealed.envelope(),
        sealed.keyId(), digest, RESULT_SCHEMA);

    if (inserted == 0) {
      // A result was already stored for this request -- a replacement finishing what a dead process
      // started. Worth a line, because it is evidence of a recovery rather than routine.
      LOGGER.info("contract B result already stored, keeping the existing row "
          + "[requestId={}, digest={}]", requestId, digest);
    } else {
      LOGGER.info("contract B result stored [requestId={}, keyId={}, digest={}, envelopeBytes={}]",
          requestId, sealed.keyId(), digest, sealed.envelope().length);
    }
    return new StoredResult(sealed.keyId(), digest);
  }

  /**
   * Reads one result for adoption.
   *
   * <p>The distinction this method exists to preserve: <strong>absent and undecryptable are not the
   * same answer.</strong> An absent row is an empty {@code Optional} — the result was adopted, or
   * purged, or never stored. A row that will not open throws. M2-ADR-018 §10 is explicit that
   * conflating them is the dangerous direction, because an undecryptable result reported as absent
   * <em>"would look like a clean re-runnable request and could resubmit to the provider."</em>
   *
   * @throws ResultEncryptionKeyUnavailableException when the row's key is not held — refuse to
   *     adopt, and surface it as itself
   * @throws ResultEnvelopeCorruptException when the ciphertext fails authentication. Nothing is
   *     deleted: the row is evidence
   */
  public Optional<String> read(String requestId) {
    requireIdentity(requestId, "a request identity is required to read a result");
    Sealed row;
    try {
      row = jdbc.queryForObject(SELECT, (rs, n) ->
          new Sealed(rs.getBytes("normalized_result"), rs.getString("encryption_key_id"),
              rs.getString("result_digest")), requestId);
    } catch (EmptyResultDataAccessException absent) {
      return Optional.empty();
    }

    byte[] plaintext;
    try {
      plaintext = codec.open(row.envelope(), requestId);
    } catch (ResultEncryptionKeyUnavailableException unavailable) {
      LOGGER.error("contract B result cannot be adopted: encryption key unavailable "
          + "[requestId={}, keyId={}]. The result is present and is not absent; do not resubmit.",
          requestId, unavailable.keyId());
      throw unavailable;
    } catch (ResultEnvelopeCorruptException corrupt) {
      LOGGER.error("contract B result failed authentication [requestId={}, keyId={}]. "
          + "Treated as corruption: not adopted, not deleted -- the row is evidence.",
          requestId, corrupt.keyId());
      throw corrupt;
    }

    String observed = sha256(plaintext);
    if (!observed.equals(row.digest())) {
      // Belt and braces over GCM, which would already have refused a modified ciphertext. This
      // catches the case the tag cannot see: a correctly sealed envelope whose digest column was
      // written from different bytes, which would mean the provenance record and the content
      // disagree about what the provider returned.
      LOGGER.error("contract B result digest mismatch [requestId={}, recorded={}, observed={}]",
          requestId, row.digest(), observed);
      throw new ResultEnvelopeCorruptException(null, "stored digest does not match the result");
    }

    LOGGER.info("contract B result read for adoption [requestId={}, digest={}]", requestId, observed);
    return Optional.of(new String(plaintext, StandardCharsets.UTF_8));
  }

  /** Whether a result row exists, without decrypting it. Used by tests and by operator tooling. */
  public boolean exists(String requestId) {
    Integer count = jdbc.queryForObject(
        "SELECT count(*) FROM core.ai_execution_result WHERE request_id = ?", Integer.class,
        requestId);
    return count != null && count > 0;
  }

  /** What was written, for the caller's provenance record. Never the result itself. */
  public record StoredResult(String keyId, String digest) {}

  private record Sealed(byte[] envelope, String keyId, String digest) {}

  /**
   * Parses the document against {@code diagnostic-proposal.v1} and re-serialises what parsed.
   *
   * <p>The re-serialisation is the control, not a tidying step. What is sealed is built from the
   * record's components, so a field outside the contract is not removed — it never had a
   * destination. The key order is fixed so the digest is stable across writes of the same content.
   */
  private byte[] canonicalise(String requestId, String normalizedResultJson) {
    if (normalizedResultJson == null || normalizedResultJson.isBlank()) {
      throw new ContractBResultRejectedException("PROPOSAL_PAYLOAD_ABSENT", "no proposal payload");
    }
    Map<String, Object> payload;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> read = json.readValue(normalizedResultJson, Map.class);
      payload = read;
    } catch (JacksonException | ClassCastException notJson) {
      // The cause is dropped deliberately: Jackson's message quotes the offending input, which on
      // this path is the learner's diagnosis.
      throw new ContractBResultRejectedException(
          "PROPOSAL_PAYLOAD_UNREADABLE", "the proposal payload is not a JSON object");
    }

    DiagnosticAssessmentProposal proposal;
    try {
      proposal = DiagnosticAssessmentProposal.parse(
          payload,
          stringOrBlank(payload.get("proposalId")),
          requestId,
          stringOrBlank(payload.get("agentRunId")),
          stringOrBlank(payload.get("contextId")));
    } catch (DiagnosticAssessmentProposal.MalformedProposalException malformed) {
      // The parser is reused rather than reimplemented: it already owns every bound in the
      // committed contract, and a second validator here would be a second thing to drift. Its
      // reason codes carry through, and the cause is kept because that class builds its messages
      // from fixed strings -- asserted by test, not assumed.
      throw new ContractBResultRejectedException(
          malformed.reasonCode(), "the payload is not a valid " + RESULT_SCHEMA, malformed);
    }

    Map<String, Object> canonical = new LinkedHashMap<>();
    canonical.put("contractVersion", proposal.contractVersion());
    canonical.put("proposalId", proposal.proposalId());
    canonical.put("requestId", proposal.requestId());
    canonical.put("agentRunId", proposal.agentRunId());
    canonical.put("diagnoses", proposal.diagnoses().stream().map(diagnosis -> {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("skillCode", diagnosis.skillCode());
      entry.put("classification", diagnosis.classification().name());
      entry.put("reason", diagnosis.reason());
      entry.put("evidenceIds", List.copyOf(diagnosis.evidenceIds()));
      return entry;
    }).toList());
    canonical.put("recommendedNextSkills", proposal.recommendedNextSkills());
    canonical.put("confidence", proposal.confidence());

    try {
      return json.writeValueAsBytes(canonical);
    } catch (JacksonException impossible) {
      throw new ContractBResultRejectedException(
          "PROPOSAL_PAYLOAD_UNREADABLE", "the validated proposal could not be serialised");
    }
  }

  private static String stringOrBlank(Object raw) {
    return raw instanceof String value ? value : "";
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException absent) {
      throw new IllegalStateException("SHA-256 is required", absent);
    }
  }

  private static void requireIdentity(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
  }
}
