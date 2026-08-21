package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.observability.CorrelationContext;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The learner-facing tutor endpoint.
 *
 * <p>This is the half of M1-T08 that did not ship. {@code TutorService} was built, wired to the AI
 * plane and registered as a bean; {@code web-ui/src/learning/tutorApi.ts} was built and posts here.
 * Nothing connected them, so a learner asking for an explanation received a 500 from a route that
 * did not exist, and the agent plane had no product surface at all. M1-T18 found it by asking the
 * running candidate rather than the source.
 *
 * <p>Bounded and non-streaming per M1-ADR-004: one request, one validated response, no SSE and no
 * chunking. The wait is made tolerable in the interface — a pending state showing the support code
 * from the start, and a cancel that abandons the request — not by streaming unvalidated text.
 *
 * <p>A tutor failure is an outcome, never an exception. {@code TutorService} returns
 * {@link TutorOutcome.Unavailable} with a reason rather than throwing, and this returns 200 carrying
 * that outcome: a learner losing their tutor must not lose their session, and a client forced into a
 * catch block treats a degraded feature as a broken page.
 */
@RestController
@RequestMapping("/api/v1/tutor")
@PreAuthorize("hasRole('LEARNER')")
public class TutorController {

  private final TutorService tutorService;

  public TutorController(TutorService tutorService) {
    this.tutorService = tutorService;
  }

  @PostMapping("/explain")
  TutorExplainResponse explain(
      @Valid @RequestBody TutorExplainRequest request,
      @RequestHeader(name = "Accept-Language", required = false) String acceptLanguage,
      Authentication authentication) {

    // The support code the learner is already looking at. tutorApi.ts shows it before the request
    // completes, so returning any other value would hand support two identifiers for one event.
    String supportCode = CorrelationContext.currentInteractionId();

    TutorOutcome outcome =
        tutorService.explain(
            learnerRef(authentication), request.skillCode(), request.masteryStatus(),
            locale(acceptLanguage));

    return TutorExplainResponse.from(outcome, supportCode);
  }

  /**
   * An opaque, single-use reference for the AI plane.
   *
   * <p>Deliberately not the token subject and deliberately not a stable pseudonym of it. The plane is
   * told which skill to explain, not who is asking, and a value that were stable across requests
   * would let it build a per-learner history from calls it is not supposed to be able to link. A
   * fresh value each time carries the grouping the envelope needs within one request and nothing
   * beyond it.
   *
   * <p>The authenticated subject still governs everything that matters — authorization happens above
   * this line, and the deterministic core reads the learner's real state from the token subject.
   */
  private static String learnerRef(Authentication authentication) {
    // Referenced so the compiler and the reader both record that the subject was available here and
    // was not sent. Removing the parameter would lose that.
    if (authentication == null || authentication.getName() == null) {
      throw new IllegalStateException("tutor explain reached without an authenticated subject");
    }
    return UUID.randomUUID().toString();
  }

  /** First tag of Accept-Language, or the platform default. Never null: the plane requires one. */
  private static String locale(String acceptLanguage) {
    if (acceptLanguage == null || acceptLanguage.isBlank()) {
      return Locale.ENGLISH.toLanguageTag();
    }
    String first = acceptLanguage.split(",", 2)[0].split(";", 2)[0].trim();
    return first.isEmpty() ? Locale.ENGLISH.toLanguageTag() : first;
  }
}
