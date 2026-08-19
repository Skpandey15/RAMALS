package io.ramals.learningplatform.content;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.admin.AdminActivityRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * Promotion is the only door to {@code VERIFIED_CONTENT}, so the tests are about who may open it and
 * what is written down when they do.
 *
 * <p>Autowires the real service rather than constructing one, because {@code @PreAuthorize} is
 * enforced by a proxy. A test that calls {@code new ContentPromotionService(...)} exercises the
 * method with the authorization annotation stripped off and would pass identically if method
 * security were switched off entirely.
 */
@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:content-promotion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
class ContentPromotionServiceTests {

  private static final UUID ITEM = UUID.fromString("01900000-0000-7000-8000-000000000411");

  @Autowired
  ContentPromotionService service;

  @MockitoBean
  ContentTrustRepository repository;

  @MockitoBean
  AdminActivityRepository auditRepository;

  // -- who may promote ----------------------------------------------------------------------------

  @Test
  @WithAnonymousUser
  @DisplayName("an unauthenticated caller cannot promote content")
  void anonymousCallersAreRefused() {
    assertThatThrownBy(() -> service.promote(ITEM, "reviewer-1"))
        .isInstanceOf(AccessDeniedException.class);

    verify(repository, never()).promote(any(), any());
  }

  @Test
  @WithMockUser(roles = "LEARNER")
  @DisplayName("a learner cannot promote content")
  void learnersAreRefused() {
    // The role that most callers hold. If method security were misconfigured this is the test that
    // would notice, because a learner reaches the same beans through the same context.
    assertThatThrownBy(() -> service.promote(ITEM, "reviewer-1"))
        .isInstanceOf(AccessDeniedException.class);

    verify(repository, never()).promote(any(), any());
  }

  @Test
  @WithMockUser(roles = "CONTENT_AUTHOR")
  @DisplayName("a content author cannot bypass durable approval")
  void contentAuthorsCannotBypassDurableApproval() {
    // The converse. A rule that denied everyone would satisfy both tests above and ship no content.
    when(repository.trustStateOf(ITEM)).thenReturn(Optional.of(TrustState.UNVERIFIED));

    assertThatThrownBy(() -> service.promote(ITEM, "reviewer-1"))
        .hasMessageContaining("durable approval request is required");
    verify(repository, never()).promote(any(), any());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  @DisplayName("an administrator cannot bypass durable approval")
  void administratorsCannotBypassDurableApproval() {
    when(repository.trustStateOf(ITEM)).thenReturn(Optional.of(TrustState.UNVERIFIED));

    assertThatThrownBy(() -> service.promote(ITEM, "admin-1"))
        .hasMessageContaining("durable approval request is required");
    verify(repository, never()).promote(any(), any());
  }

  // -- what is written down -----------------------------------------------------------------------

  @Test
  @WithMockUser(roles = "CONTENT_AUTHOR")
  @DisplayName("a direct promotion bypass is audited as refused")
  void directPromotionBypassIsAudited() {
    when(repository.trustStateOf(ITEM)).thenReturn(Optional.of(TrustState.UNVERIFIED));

    assertThatThrownBy(() -> service.promote(ITEM, "reviewer-1"))
        .hasMessageContaining("durable approval request is required");

    verify(auditRepository).append(
        eq("reviewer-1"), eq("PROMOTE_CONTENT"), eq("ASSESSMENT_ITEM_VERSION"), eq(ITEM),
        eq("REJECTED"), any(), any(), any());
  }

  @Test
  @WithMockUser(roles = "CONTENT_AUTHOR")
  @DisplayName("a refused promotion is audited too")
  void refusedPromotionIsAudited() {
    when(repository.trustStateOf(ITEM)).thenReturn(Optional.of(TrustState.REJECTED));

    assertThatThrownBy(() -> service.promote(ITEM, "reviewer-1"))
        .isInstanceOf(ContentPromotionService.PromotionRefusedException.class);

    // An audit holding only successes cannot answer "did anyone try", which is the question asked
    // after something goes wrong.
    verify(auditRepository).append(
        eq("reviewer-1"), eq("PROMOTE_CONTENT"), eq("ASSESSMENT_ITEM_VERSION"), eq(ITEM),
        eq("REJECTED"), any(), any(), any());
    verify(repository, never()).promote(any(), any());
  }

  // -- which transitions are allowed ---------------------------------------------------------------

  @Test
  @WithMockUser(roles = "CONTENT_AUTHOR")
  @DisplayName("rejected content is not promoted")
  void rejectedContentIsNotPromoted() {
    when(repository.trustStateOf(ITEM)).thenReturn(Optional.of(TrustState.REJECTED));

    // Rejected content is regenerated or corrected, not approved on a second click. Otherwise a
    // rejection is only a suggestion.
    assertThatThrownBy(() -> service.promote(ITEM, "reviewer-1"))
        .hasMessageContaining("rejected content cannot be promoted");
  }

  @Test
  @WithMockUser(roles = "CONTENT_AUTHOR")
  @DisplayName("promoting already-verified content records no second review")
  void alreadyVerifiedContentIsNotReaudited() {
    when(repository.trustStateOf(ITEM)).thenReturn(Optional.of(TrustState.VERIFIED_CONTENT));

    service.promote(ITEM, "reviewer-2");

    // Idempotent, but silent. A second audit entry would read as a second human review of content
    // nobody looked at twice.
    verify(repository, never()).promote(any(), any());
    verify(auditRepository, never()).append(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @WithMockUser(roles = "CONTENT_AUTHOR")
  @DisplayName("content that does not exist cannot be promoted")
  void missingContentIsRefused() {
    when(repository.trustStateOf(ITEM)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.promote(ITEM, "reviewer-1"))
        .isInstanceOf(ContentPromotionService.PromotionRefusedException.class);
  }

  @Test
  @WithMockUser(roles = "CONTENT_AUTHOR")
  @DisplayName("a promotion must name a reviewer even when the caller is authorized")
  void blankReviewerIsRefused() {
    // Holding the role is not the same as being the person who reviewed the item, and the row has to
    // carry a name a later reader can go and ask.
    assertThatThrownBy(() -> service.promote(ITEM, "  "))
        .isInstanceOf(ContentPromotionService.PromotionRefusedException.class);

    verify(repository, never()).promote(any(), any());
  }
}
