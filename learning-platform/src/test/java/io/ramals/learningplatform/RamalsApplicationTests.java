package io.ramals.learningplatform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD="
})
class RamalsApplicationTests {

  @Test
  void contextLoads() {
  }
}
