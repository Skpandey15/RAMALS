package io.ramals.learningplatform.orchestration;

import org.springframework.transaction.support.TransactionTemplate;

/** The production unit of work: one local database transaction per group of writes. */
public class SpringWorkflowUnitOfWork implements WorkflowUnitOfWork {

  private final TransactionTemplate transactions;

  public SpringWorkflowUnitOfWork(TransactionTemplate transactions) {
    this.transactions = transactions;
  }

  @Override
  public void inOneTransaction(Runnable writes) {
    transactions.executeWithoutResult(status -> writes.run());
  }
}
