package io.ramals.learningplatform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AdminIdentityProviderClientTests {

  private static final String BASE_URL = "https://keycloak.example.test";

  private MockRestServiceServer server;
  private AdminIdentityProviderClient client;

  @BeforeEach
  void setUp() {
    AdminIdentityProperties properties = new AdminIdentityProperties();
    properties.setBaseUrl(BASE_URL);
    properties.setRealm("ramals");
    properties.setClientId("ramals-identity-admin");
    properties.setClientSecret("test-secret");

    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    client = AdminIdentityProviderClient.forTesting(properties, builder.build());
  }

  @Test
  void listUsersPaginatesPastTwoHundredIdentities() {
    expectToken();
    server.expect(requestTo(BASE_URL + "/admin/realms/ramals/users?first=0&max=200"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(usersJson(0, 200), MediaType.APPLICATION_JSON));
    for (int i = 0; i < 200; i++) {
      expectRoles("user-" + i);
    }
    server.expect(requestTo(BASE_URL + "/admin/realms/ramals/users?first=200&max=200"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(usersJson(200, 1), MediaType.APPLICATION_JSON));
    expectRoles("user-200");

    List<AdminIdentityUser> users = client.listUsers();

    assertThat(users).hasSize(201);
    assertThat(users.getFirst().id()).isEqualTo("user-0");
    assertThat(users.getLast().id()).isEqualTo("user-200");
    server.verify();
  }

  @Test
  void getUserFetchesTheTargetDirectlyInsteadOfDependingOnListPagination() {
    expectToken();
    server.expect(requestTo(BASE_URL + "/admin/realms/ramals/users/user-999"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(
            "{\"id\":\"user-999\",\"username\":\"late-user\",\"email\":\"late@example.test\",\"enabled\":true}",
            MediaType.APPLICATION_JSON));
    expectRoles("user-999");

    AdminIdentityUser user = client.getUser("user-999");

    assertThat(user.id()).isEqualTo("user-999");
    assertThat(user.username()).isEqualTo("late-user");
    server.verify();
  }

  @Test
  void serviceAccountIsDetectedFromKeycloakMetadataWithoutServiceRealmRole() {
    expectToken();
    server.expect(requestTo(BASE_URL + "/admin/realms/ramals/users/workload-1"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(
            "{\"id\":\"workload-1\",\"username\":\"service-account-ramals-identity-admin\","
                + "\"enabled\":true,\"serviceAccountClientId\":\"ramals-identity-admin\"}",
            MediaType.APPLICATION_JSON));

    assertThat(client.isServiceAccount("workload-1")).isTrue();
    server.verify();
  }

  private void expectToken() {
    server.expect(requestTo(BASE_URL + "/realms/ramals/protocol/openid-connect/token"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(
            "{\"access_token\":\"token\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
  }

  private void expectRoles(String userId) {
    server.expect(requestTo(
            BASE_URL + "/admin/realms/ramals/users/" + userId + "/role-mappings/realm/composite"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
  }

  private static String usersJson(int first, int count) {
    StringBuilder json = new StringBuilder("[");
    for (int offset = 0; offset < count; offset++) {
      if (offset > 0) {
        json.append(',');
      }
      int index = first + offset;
      json.append("{\"id\":\"user-")
          .append(index)
          .append("\",\"username\":\"staff-")
          .append(index)
          .append("\",\"enabled\":true}");
    }
    return json.append(']').toString();
  }
}
