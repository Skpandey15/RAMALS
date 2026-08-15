package io.ramals.learningplatform.learning;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learning-session lifecycle, scoped to the authenticated learner. Start resumes an open session;
 * transitions carry the expected version for optimistic concurrency.
 */
@RestController
@RequestMapping("/api/v1/me/learning-sessions")
@PreAuthorize("hasRole('LEARNER')")
public class LearningSessionController {

  private final LearningSessionService service;

  public LearningSessionController(LearningSessionService service) {
    this.service = service;
  }

  @PostMapping
  ResponseEntity<SessionResponse> start(
      Authentication authentication, @Valid @RequestBody StartSessionRequest request) {
    SessionStartResult result =
        service.start(authentication.getName(), request.domainCode(), request.versionCode());
    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status).body(SessionResponse.from(result.session()));
  }

  @GetMapping
  List<SessionResponse> list(Authentication authentication) {
    return service.list(authentication.getName()).stream().map(SessionResponse::from).toList();
  }

  @GetMapping("/{sessionId}")
  SessionResponse get(Authentication authentication, @PathVariable String sessionId) {
    return SessionResponse.from(service.get(authentication.getName(), sessionId));
  }

  @PostMapping("/{sessionId}/transitions")
  SessionResponse transition(
      Authentication authentication,
      @PathVariable String sessionId,
      @Valid @RequestBody SessionTransitionRequest request) {
    return SessionResponse.from(service.transition(authentication.getName(), sessionId, request));
  }
}
