package io.ramals.learningplatform.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminLearnerServiceTests {

  private static final UUID LEARNER =
      UUID.fromString("01900000-0000-7000-8000-000000000111");

  private final AdminLearnerRepository repository = mock(AdminLearnerRepository.class);
  private final AdminActivityRepository audit = mock(AdminActivityRepository.class);
  private final AdminLearnerService service = new AdminLearnerService(repository, audit);

  @Test
  void concurrentStatusChangeFailsClosedInsteadOfOverwritingNewState() {
    AdminLearnerSummary active = learner("ACTIVE");
    when(repository.findById(LEARNER)).thenReturn(Optional.of(active));
    when(repository.updateStatus(LEARNER, "ACTIVE", "CLOSED")).thenReturn(0);

    assertThatThrownBy(() -> service.changeStatus("admin-1", LEARNER, "CLOSED"))
        .isInstanceOf(AdminLearnerStateConflictException.class);

    verify(repository).updateStatus(LEARNER, "ACTIVE", "CLOSED");
  }

  private static AdminLearnerSummary learner(String status) {
    return new AdminLearnerSummary(
        LEARNER,
        "learner-subject",
        status,
        "Ada",
        "Learner",
        "ada@example.test",
        "+919999999999",
        "IN",
        "Bengaluru",
        true,
        true,
        "ONBOARDED",
        Instant.parse("2026-08-30T00:00:00Z"),
        Instant.parse("2026-08-31T00:00:00Z"));
  }
}
