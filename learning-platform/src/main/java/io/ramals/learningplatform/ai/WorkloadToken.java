package io.ramals.learningplatform.ai;

/**
 * Supplies the workload access token an AI client presents to the AI plane.
 *
 * <p>An interface rather than the concrete provider so a client's dependency is "something that can
 * authenticate as this workload" rather than "the Keycloak client-credentials cache". The clients
 * require it, which is the point: the AI plane rejects an unauthenticated request, so a client built
 * without one can only ever produce 401s — and those degrade into "the AI plane is unreachable",
 * which is indistinguishable at the call site from the service genuinely being down.
 */
@FunctionalInterface
public interface WorkloadToken {

  /**
   * Returns a currently valid access token, obtaining or refreshing one if needed.
   *
   * @throws AiUnavailableException when a token cannot be obtained
   */
  String accessToken();

  /**
   * Whether this environment has a workload identity at all.
   *
   * <p>Lets the ports refuse at wiring time rather than at call time. A client that can reach the AI
   * plane but cannot authenticate to it produces nothing but 401s, which degrade into "the AI plane
   * is unreachable" and read as an outage rather than as the configuration mistake they are.
   */
  default boolean available() {
    return true;
  }

  /** The identity used when none is configured. Refuses without contacting anything. */
  static WorkloadToken unavailable(String detail) {
    return new WorkloadToken() {
      @Override
      public String accessToken() {
        throw new AiUnavailableException("AI_NOT_CONFIGURED", detail, FailureOrigin.CALLER);
      }

      @Override
      public boolean available() {
        return false;
      }
    };
  }
}
