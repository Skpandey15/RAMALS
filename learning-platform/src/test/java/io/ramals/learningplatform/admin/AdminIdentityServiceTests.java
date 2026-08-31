package io.ramals.learningplatform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminIdentityServiceTests {

  private final AdminIdentityProviderClient provider = mock(AdminIdentityProviderClient.class);
  private final AdminActivityRepository audit = mock(AdminActivityRepository.class);
  private final AdminIdentityService service = new AdminIdentityService(provider, audit);

  @Test
  void adminCannotMutateOwnIdentity() {
    assertThatThrownBy(() -> service.setEnabled("same-subject", "same-subject", false))
        .isInstanceOf(IllegalArgumentException.class);
    verify(provider, never()).setEnabled("same-subject", false);
  }

  @Test
  void learnerIdentityCannotBeChangedThroughStaffAdministration() {
    when(provider.effectiveRealmRoles("learner-1")).thenReturn(Set.of("LEARNER"));

    assertThatThrownBy(() -> service.setEnabled("admin-1", "learner-1", false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.addRole("admin-1", "learner-1", "CONTENT_AUTHOR"))
        .isInstanceOf(IllegalArgumentException.class);
    verify(provider, never()).setEnabled("learner-1", false);
    verify(provider, never()).addRealmRole("learner-1", "CONTENT_AUTHOR");
  }

  @Test
  void adminAndServiceTargetsRemainOutOfBand() {
    when(provider.effectiveRealmRoles("admin-2")).thenReturn(Set.of("ADMIN"));
    when(provider.effectiveRealmRoles("service-1")).thenReturn(Set.of("SERVICE"));

    assertThatThrownBy(() -> service.setEnabled("admin-1", "admin-2", false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.addRole("admin-1", "service-1", "INSTRUCTOR"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void onlyExplicitStaffRolesAreManageable() {
    when(provider.effectiveRealmRoles("user-2")).thenReturn(Set.of());

    assertThatThrownBy(() -> service.addRole("admin-1", "user-2", "ADMIN"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.addRole("admin-1", "user-2", "LEARNER"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void identityMutationIsNotAttemptedWhenDurableIntentAuditFails() {
    when(provider.effectiveRealmRoles("user-2")).thenReturn(Set.of("CONTENT_AUTHOR"));
    doThrow(new IllegalStateException("audit unavailable"))
        .when(audit).append(eq("admin-1"), eq("SET_IDENTITY_ENABLED"), eq("IDENTITY_USER"),
            nullable(UUID.class), eq("ATTEMPTED"), eq("requested: disabled"),
            nullable(String.class), nullable(String.class));

    assertThatThrownBy(() -> service.setEnabled("admin-1", "user-2", false))
        .isInstanceOf(IllegalStateException.class);
    verify(provider, never()).setEnabled("user-2", false);
  }

  @Test
  void completedIdentityMutationIsNotMisreportedWhenSuccessAuditFails() {
    when(provider.effectiveRealmRoles("user-2")).thenReturn(Set.of("CONTENT_AUTHOR"));
    when(provider.listUsers()).thenReturn(List.of(
        new AdminIdentityUser("user-2", "staff", "staff@example.test", false,
            Set.of("CONTENT_AUTHOR"))));
    doThrow(new IllegalStateException("completion audit unavailable"))
        .when(audit).append(eq("admin-1"), eq("SET_IDENTITY_ENABLED"), eq("IDENTITY_USER"),
            nullable(UUID.class), eq("SUCCESS"), eq("disabled"),
            nullable(String.class), nullable(String.class));

    AdminIdentityUser result = service.setEnabled("admin-1", "user-2", false);

    assertThat(result.enabled()).isFalse();
    verify(provider).setEnabled("user-2", false);
  }
}
