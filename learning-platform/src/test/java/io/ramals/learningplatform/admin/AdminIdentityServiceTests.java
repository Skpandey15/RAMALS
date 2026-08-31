package io.ramals.learningplatform.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
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
  void learnerCannotBePromotedIntoStaffPersona() {
    when(provider.effectiveRealmRoles("learner-1")).thenReturn(Set.of("LEARNER"));

    assertThatThrownBy(() -> service.addRole("admin-1", "learner-1", "CONTENT_AUTHOR"))
        .isInstanceOf(IllegalArgumentException.class);
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
}
