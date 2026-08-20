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
}
