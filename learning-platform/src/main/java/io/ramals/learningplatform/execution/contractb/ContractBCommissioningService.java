package io.ramals.learningplatform.execution.contractb;

import io.ramals.learningplatform.observability.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Commissions one Contract B execution, deliberately, on request.
 *
 * <p>Contract B had no entry point at all: nothing in the platform called {@code admit} or
 * {@code submit}, so the flag that was supposed to activate it activated nothing. This is that
 * entry point, and it is deliberately the narrowest one that can exist.
 *
 * <p><strong>It diverts no traffic.</strong> Contract A's path — the outbox, the dispatcher,
 * {@code AdaptationOutboxProcessor} — is untouched, and no learner request reaches this class. An
 * execution exists here only because an operator asked for one. That is what makes this safe to
 * enable in a development environment while the durable path is still being qualified: turning it on
 * changes what is <em>possible</em>, never what happens on its own.
 *
 * <p><strong>Absent when disabled.</strong> Conditional on {@code ramals.contract-b.enabled}, so
 * with the flag off this bean does not exist, its controller does not exist, and the platform is
 * byte-identical to one built without Contract B commissioning. Not a runtime {@code if} — a
 * missing bean, because a runtime check is a thing that can be got wrong later.
 *
 * <p><strong>The identifiers are server-derived, and that is a contract rather than a convention.</strong>
 * The Definition of Done requires the {@code custom_id} carried to the provider to be the
 * server-derived idempotency key, never a caller-supplied value: correlation that a caller can
 * influence is correlation that can be pointed at someone else's execution. Both are generated here
 * and neither is accepted from the request.
 */
@Service
@ConditionalOnProperty(prefix = "ramals.contract-b", name = "enabled", havingValue = "true")
public class ContractBCommissioningService {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ContractBCommissioningService.class);

  private final ContractBExecutionService lifecycle;

  public ContractBCommissioningService(ContractBExecutionService lifecycle) {
    this.lifecycle = lifecycle;
  }

  /**
   * Admits and submits exactly one durable execution.
   *
   * <p>Admission first, then submission, and never the other way round. That order is the
   * write-ahead discipline the whole design rests on: the durable row exists before the provider is
   * called, so a process that dies mid-call leaves "sent, unacknowledged" — recoverable — rather
   * than a provider execution nothing knows about.
   *
   * <p>Everything after admission is the existing lifecycle, unchanged. Fencing, the single
   * submission, the refusal to retry, and the ambiguity semantics are
   * {@link ContractBExecutionService}'s and are not re-implemented here. This method's whole job is
   * to be the caller that was missing.
   *
   * @return the state the execution holds after one submission attempt — including
   *     {@code UNKNOWN_TERMINAL}, which is a legitimate outcome and not an error
   */
  public Commissioned commission(String model, String modelRoute, String prompt,
      int maxOutputTokens) {
    // A fresh identity per commissioning. There is no workflow run behind this path to derive one
    // from, and inventing a stable-looking key would be worse than an honest random one: a key that
    // repeated across two commissionings would make two separate executions look like one retry.
    String requestId = "req-" + UuidV7.generate();
    String idempotencyKey = "idem-" + requestId;

    boolean admitted = lifecycle.admit(requestId, idempotencyKey, "anthropic", model, modelRoute);
    if (!admitted) {
      // Admission is idempotent on the idempotency key. A fresh UUIDv7 cannot collide in practice,
      // so this means something is wrong with the identity generation rather than that a duplicate
      // request arrived -- and continuing to submit would be submitting against someone else's row.
      throw new IllegalStateException(
          "contract B admission was refused for a freshly generated identity");
    }

    DurableExecutionState state = lifecycle.submit(requestId, new DurableSubmissionCommand(
        requestId, idempotencyKey, digestOf(model, prompt, maxOutputTokens), model, maxOutputTokens,
        List.of(new DurableSubmissionCommand.Turn("user", prompt))));

    LOGGER.info("contract B execution commissioned [requestId={}, state={}]", requestId, state);
    return new Commissioned(requestId, state);
  }

  /** What was commissioned, and where it got to. */
  public record Commissioned(String requestId, DurableExecutionState state) {}

  /**
   * A digest of what was asked for.
   *
   * <p>Provenance rather than integrity: it records what this execution was commissioned to do, so a
   * result adopted later can be checked against the request that produced it. It deliberately covers
   * the prompt, which is why it is a hash — the digest travels to the AI plane and a plain copy of
   * the prompt would put learner-derived text somewhere it does not need to be.
   */
  private static String digestOf(String model, String prompt, int maxOutputTokens) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      String canonical = model + "\n" + maxOutputTokens + "\n" + prompt;
      return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is required to commission an execution", unavailable);
    }
  }
}
