package io.ramals.learningplatform.observability;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class DatabaseProbeController {

  private final JdbcTemplate jdbcTemplate;

  public DatabaseProbeController(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @GetMapping("/database-probe")
  @PreAuthorize("hasRole('SERVICE') or (hasRole('ADMIN') and @mfaAuthorization.hasMfa(authentication))")
  Map<String, String> probe() {
    Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
    return Map.of("status", value != null && value == 1 ? "UP" : "DOWN");
  }
}
