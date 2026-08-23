package io.ramals.learningplatform.diagnosticassessment;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;
import io.ramals.learningplatform.execution.AiExecutionRepository;
import io.ramals.learningplatform.grounding.ProposalGateDecisionPort;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the execution success and its gate decision in one local transaction.
 *
 * <p>Calls the repositories rather than the execution service on purpose: that service wraps each
 * write in {@code REQUIRES_NEW}, which is right for accounting that must survive a caller's rollback
 * and exactly wrong here, where the two rows must share a fate.
 *
 * <p>The transaction is opened explicitly through a {@link TransactionTemplate} rather than declared
 * with {@code @Transactional}. A declared annotation only takes effect through a Spring proxy, so
 * the guarantee would quietly disappear for any caller holding a directly constructed instance --
 * including its own tests, which would then pass while proving nothing. Opening it here makes the
 * atomicity a property of the class instead of a property of how the class was obtained.
 *
 * <p>Nothing in this class talks to a provider, so the transaction it opens is short and local.
 */
@Component
public class TransactionalDiagnosticOutcomeWriter implements DiagnosticOutcomeWriter {

  private final AiExecutionRepository executions;
  private final ProposalGateDecisionPort decisions;
  private final TransactionTemplate transactions;

  public TransactionalDiagnosticOutcomeWriter(
      AiExecutionRepository executions,
      ProposalGateDecisionPort decisions,
      PlatformTransactionManager transactionManager) {
    this.executions = executions;
    this.decisions = decisions;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public void commitSuccess(
      DiagnosticAssessmentRequest request,
      AiProposalEnvelope envelope,
      Instant startedAt,
      Instant completedAt,
      PendingDecision decision) {
    transactions.executeWithoutResult(
        status -> {
          executions.insertDiagnosticAssessmentSuccess(request, envelope, startedAt, completedAt);
          switch (decision) {
            case PendingDecision.Gated gated ->
                decisions.appendDecision(gated.proposal(), gated.result(), gated.correlation());
            case PendingDecision.PreParse preParse ->
                decisions.appendPreParseRejection(preParse.rejection());
          }
        });
  }
}
