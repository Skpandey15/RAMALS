package io.ramals.learningplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the Zero Trust rule that secrets are absent from source. Application configuration must
 * reference secrets only through environment placeholders, and the committed .env.example must ship
 * with empty secret values.
 */
class SecretHygieneTests {

  private static final Pattern YAML_SECRET =
      Pattern.compile("(?i)^\\s*(password|secret|client-secret):\\s*(.*)$");
  private static final Pattern ENV_SECRET = Pattern.compile("(?i)^([A-Z0-9_]*(PASSWORD|SECRET))=(.*)$");

  @Test
  void applicationConfigReferencesSecretsOnlyThroughEnvironmentPlaceholders() throws IOException {
    for (String resource : List.of("/application.yml", "/application-prod.yml", "/application-shared.yml")) {
      for (String line : resourceLines(resource)) {
        Matcher matcher = YAML_SECRET.matcher(line);
        if (matcher.matches()) {
          String value = matcher.group(2).trim();
          assertThat(value)
              .as("secret in %s must be an env placeholder, not a literal: %s", resource, line.trim())
              .matches("^(\\$\\{.*}|\"?\\$\\{.*}\"?)?$");
        }
      }
    }
  }

  @Test
  void envExampleShipsWithEmptySecretValues() throws IOException {
    for (String line : envExampleLines()) {
      Matcher matcher = ENV_SECRET.matcher(line.trim());
      if (matcher.matches()) {
        assertThat(matcher.group(3))
            .as("secret env key must be empty in .env.example: %s", line.trim())
            .isEmpty();
      }
    }
  }

  private List<String> resourceLines(String resource) throws IOException {
    try (var input = getClass().getResourceAsStream(resource)) {
      assertThat(input).as("resource %s", resource).isNotNull();
      return List.of(new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R"));
    }
  }

  private List<String> envExampleLines() throws IOException {
    Path fromModule = Path.of("..", ".env.example");
    Path envExample = Files.exists(fromModule) ? fromModule : Path.of(".env.example");
    assertThat(Files.exists(envExample)).as(".env.example at %s", envExample.toAbsolutePath()).isTrue();
    return Files.readAllLines(envExample, StandardCharsets.UTF_8);
  }
}
