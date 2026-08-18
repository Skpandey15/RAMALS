package io.ramals.learningplatform.observability;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class DatabaseProbeController {

  private final DatabaseProbeService service;

  public DatabaseProbeController(DatabaseProbeService service) {
    this.service = service;
  }

  @GetMapping("/database-probe")
  @PreAuthorize("hasRole('SERVICE') or (hasRole('ADMIN') and @mfaAuthorization.hasMfa(authentication))")
  Map<String, String> probe() {
    return service.probe();
  }
}
