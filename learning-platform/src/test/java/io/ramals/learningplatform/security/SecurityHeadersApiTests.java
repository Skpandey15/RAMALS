package io.ramals.learningplatform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:security-headers;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class SecurityHeadersApiTests {

  @Autowired
  MockMvc mockMvc;

  @Test
  void responsesCarryHardenedSecurityHeaders() throws Exception {
    mockMvc.perform(get("/api/v1/me"))
        .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string(
            "Permissions-Policy", "geolocation=(), camera=(), microphone=(), payment=(), usb=()"));
  }
}
