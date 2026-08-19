package io.ramals.learningplatform.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.admin.AdminActivityRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class ApprovalRequestServiceTests {
  private static final UUID REQUEST = UUID.fromString("01900000-0000-7000-8000-000000000701");
  private static final UUID CANDIDATE = UUID.fromString("01900000-0000-7000-8000-000000000702");
  private static final UUID ITEM = UUID.fromString("01900000-0000-7000-8000-000000000703");
  private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

  @Mock ApprovalRequestRepository approvals;
  @Mock AssessmentCandidateRevisionRepository candidates;
  @Mock AdminActivityRepository audit;
  private ApprovalRequestService service;

  @BeforeEach
  void setUp() {
    service = new ApprovalRequestService(approvals, candidates, audit,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void approveRevalidatesPromotesAndAuditsInOrder() {
    ApprovalRequest request = pending();
    AssessmentCandidateRevision candidate = candidate();
    when(approvals.findForUpdate(REQUEST)).thenReturn(Optional.of(request));
    when(approvals.findCommand("reviewer", "APPROVE", REQUEST, "approve-1"))
        .thenReturn(Optional.empty());
    when(approvals.candidateForUpdate(CANDIDATE, 1)).thenReturn(Optional.of(candidate));
    when(approvals.candidateStillEligible(candidate)).thenReturn(true);
    when(approvals.promoteCandidate(candidate, "reviewer")).thenReturn(ITEM);
    when(approvals.find(REQUEST)).thenReturn(Optional.of(approved()));

    ApprovalRequest result = service.approve(REQUEST, "reviewer", "approve-1");

    assertThat(result.state()).isEqualTo(ApprovalState.APPROVED);
    verify(approvals).promoteCandidate(candidate, "reviewer");
    verify(approvals).transition(REQUEST, ApprovalState.APPROVAL_REQUIRED,
        ApprovalState.APPROVED, "reviewer", "human approval and final deterministic revalidation", ITEM);
    verify(approvals).insertCommand(eq("reviewer"), eq("APPROVE"), eq(REQUEST), eq("approve-1"),
        any(), eq(ApprovalState.APPROVED), eq(ITEM));
    verify(audit).appendWithinTransaction(eq("reviewer"), eq("REVIEW_APPROVAL_REQUEST"),
        eq("ASSESSMENT_APPROVAL_REQUEST"), eq(REQUEST), eq("APPROVED"), any(), any(), any());
  }

  @Test
  void staleApprovalSupersedesWithoutPromotion() {
    ApprovalRequest request = pending();
    AssessmentCandidateRevision candidate = candidate();
    when(approvals.findForUpdate(REQUEST)).thenReturn(Optional.of(request));
    when(approvals.findCommand("reviewer", "APPROVE", REQUEST, "approve-2"))
        .thenReturn(Optional.empty());
    when(approvals.candidateForUpdate(CANDIDATE, 1)).thenReturn(Optional.of(candidate));
    when(approvals.candidateStillEligible(candidate)).thenReturn(false);
    when(approvals.find(REQUEST)).thenReturn(Optional.of(superseded()));

    assertThat(service.approve(REQUEST, "reviewer", "approve-2").state())
        .isEqualTo(ApprovalState.SUPERSEDED);
    verify(approvals, never()).promoteCandidate(any(), any());
    verify(approvals).transition(REQUEST, ApprovalState.APPROVAL_REQUIRED,
        ApprovalState.SUPERSEDED, "reviewer", "final deterministic revalidation failed", null);
  }

  @Test
  void rejectionRequiresReasonAndIsTerminal() {
    when(approvals.findForUpdate(REQUEST)).thenReturn(Optional.of(pending()));
    when(approvals.findCommand("reviewer", "REJECT", REQUEST, "reject-1"))
        .thenReturn(Optional.empty());
    when(approvals.find(REQUEST)).thenReturn(Optional.of(rejected()));

    assertThat(service.reject(REQUEST, "reviewer", "reject-1", "ambiguous objective").state())
        .isEqualTo(ApprovalState.REJECTED);
    verify(approvals).transition(REQUEST, ApprovalState.APPROVAL_REQUIRED,
        ApprovalState.REJECTED, "reviewer", "ambiguous objective", null);
    assertThatThrownBy(() -> service.reject(REQUEST, "reviewer", "reject-2", " "))
        .isInstanceOf(ApprovalRequestException.class)
        .hasMessageContaining("non-blank reason");
  }

  @Test
  void sameCommandRetryReturnsCommittedTerminalResultWithoutRepeatingPromotion() {
    ApprovalRequest request = approved();
    when(approvals.findForUpdate(REQUEST)).thenReturn(Optional.of(request));
    when(approvals.findCommand("reviewer", "APPROVE", REQUEST, "approve-1"))
        .thenReturn(Optional.of(new ApprovalRequestRepository.CommandResult(
            ApprovalState.APPROVED, ITEM, fingerprint("APPROVE", REQUEST + ":"))));
    when(approvals.find(REQUEST)).thenReturn(Optional.of(request));

    assertThat(service.approve(REQUEST, "reviewer", "approve-1")).isSameAs(request);
    verify(approvals, never()).promoteCandidate(any(), any());
    verify(approvals, never()).transition(any(), any(), any(), any(), any(), any());
  }

  private static ApprovalRequest pending() {
    return request(ApprovalState.APPROVAL_REQUIRED, null);
  }

  private static ApprovalRequest approved() {
    return request(ApprovalState.APPROVED, ITEM);
  }

  private static ApprovalRequest rejected() {
    return request(ApprovalState.REJECTED, null);
  }

  private static ApprovalRequest superseded() {
    return request(ApprovalState.SUPERSEDED, null);
  }

  private static ApprovalRequest request(ApprovalState state, UUID item) {
    return new ApprovalRequest(REQUEST, CANDIDATE, 1, state, "{}", "a".repeat(64),
        "proposal-1", "1.0", "ASSESSMENT", "v1", "default", null, "prompt-v1",
        "m1-t12-policy-v1", "spring-content-promotion-v1", "interaction-1", "creator", NOW,
        NOW, NOW.plusSeconds(3600), state == ApprovalState.APPROVAL_REQUIRED ? null : "reviewer",
        state == ApprovalState.APPROVAL_REQUIRED ? null : NOW, null, item);
  }

  private static AssessmentCandidateRevision candidate() {
    return new AssessmentCandidateRevision(CANDIDATE, 1, "proposal-1", UUID.randomUUID(),
        "AI_ITEM", "KAFKA_TOPIC", "TOPIC_DEFINE", "SINGLE_CHOICE", "FOUNDATIONAL", "{}",
        "a".repeat(64), "UNVERIFIED", "1.0", "ASSESSMENT", "v1", "default", null,
        "model unavailable", "prompt-v1", "interaction-1", "creator", NOW, "creator", "intake-1",
        "b".repeat(64));
  }

  private static String fingerprint(String operation, String value) {
    // The retry test uses the service's stable command shape; this value is intentionally generated
    // through the same digest format used by the production command boundary.
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256")
          .digest((operation + "\n" + value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder();
      for (byte b : digest) result.append(String.format("%02x", b));
      return result.toString();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}
