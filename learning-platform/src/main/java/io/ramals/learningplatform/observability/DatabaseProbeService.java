package io.ramals.learningplatform.observability;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseProbeService {

  private final JdbcTemplate jdbcTemplate;

  public DatabaseProbeService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Map<String, String> probe() {
    Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
    return Map.of("status", value != null && value == 1 ? "UP" : "DOWN");
  }
}
