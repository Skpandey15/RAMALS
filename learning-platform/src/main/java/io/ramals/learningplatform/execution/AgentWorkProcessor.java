package io.ramals.learningplatform.execution;

/** Executes one claimed item outside the claim transaction. */
public interface AgentWorkProcessor {
  void process(ClaimedAgentWork work);
  default void recordTerminalFailure(ClaimedAgentWork work, String errorCode) { }
}
