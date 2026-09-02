package io.ramals.learningplatform.registration;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KeycloakVerificationRedirectTests {

  @Test
  void verificationEmailReturnsToTheConfiguredRamalsClient() {
    RegistrationProperties properties = new RegistrationProperties();
    properties.getKeycloak().setBaseUrl("http://keycloak:8080");
    properties.getKeycloak().setRealm("ramals");
    properties.getKeycloak().setClientId("ramals-registration-admin");
    properties.getKeycloak().setClientSecret("test-secret");
    properties.getKeycloak().setVerificationClientId("ramals-web-ui");
    properties.getKeycloak().setVerificationRedirectUri("http://localhost:8080/");

    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    KeycloakRegistrationAdminClient client =
        new KeycloakRegistrationAdminClient(properties, builder.build());

    server.expect(once(), requestTo(
            "http://keycloak:8080/realms/ramals/protocol/openid-connect/token"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"access_token\":\"admin-token\",\"expires_in\":300}",
            MediaType.APPLICATION_JSON));
    server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(
            "http://keycloak:8080/admin/realms/ramals/users/subject-1/send-verify-email")))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(queryParam("client_id", "ramals-web-ui"))
        // The URI-template value is percent-encoded on the wire; Keycloak decodes the query value
        // before validating it against the client's registered redirect URIs.
        .andExpect(queryParam("redirect_uri", "http%3A%2F%2Flocalhost%3A8080%2F"))
        .andRespond(withSuccess());

    client.sendVerificationEmail("subject-1");

    server.verify();
  }
}
