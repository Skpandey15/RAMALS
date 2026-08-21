package io.ramals.learningplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;

/**
 * What the two tiers are actually bound to, once application.yml has been read.
 *
 * <p>Separate from {@link RateLimitPropertiesContractTests}, which compares the YAML text against
 * the Java field defaults. This asserts the result of binding — that the values a running
 * application ends up holding are the intended ones, and that each environment variable moves the
 * tier it names and only that tier.
 *
 * <p>Both angles are needed. The text comparison catches a default edited in one file and not the
 * other; this catches a placeholder that parses but binds somewhere unintended — a `subject:` block
 * nested at the wrong depth, say, which would read as a top-level override and quietly retune the
 * shared IP bucket instead of the per-learner one. That is the shape of the original defect, so it
 * is worth being able to fail on it directly.
 *
 * <p>Uses {@link ConfigDataApplicationContextInitializer} so the real application.yml is loaded
 * without starting the application: no database, no web server, no Flyway.
 */
class RateLimitTierBindingTests {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(RateLimitProperties.class)
  static class BindingOnly {}

  private static ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            org.springframework.boot.autoconfigure.AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(BindingOnly.class);
  }

  @Test
  @DisplayName("with no overrides, the pre-authentication IP tier binds to 600/300")
  void ipTierDefaults() {
    runner()
        .run(
            context -> {
              RateLimitProperties properties = context.getBean(RateLimitProperties.class);
              // The figure everyone behind one NAT shares. It was 120/60 for a release, which is
              // what R1 Run A measured: 2,153 of 12,417 requests refused at a sustained 60 rps
              // from a single address.
              assertThat(properties.getCapacity())
                  .as("shared IP bucket capacity")
                  .isEqualTo(600);
              assertThat(properties.getRefillPerSecond())
                  .as("shared IP bucket refill per second")
                  .isEqualTo(300.0);
            });
  }

  @Test
  @DisplayName("with no overrides, the per-subject tier binds to 120/60")
  void subjectTierDefaults() {
    runner()
        .run(
            context -> {
              RateLimitProperties.Subject subject =
                  context.getBean(RateLimitProperties.class).getSubject();
              assertThat(subject.getCapacity()).as("per-learner capacity").isEqualTo(120);
              assertThat(subject.getRefillPerSecond())
                  .as("per-learner refill per second")
                  .isEqualTo(60.0);
            });
  }

  @Test
  @DisplayName("rate limiting binds on by default")
  void enabledByDefault() {
    runner().run(context -> assertThat(context.getBean(RateLimitProperties.class).isEnabled()).isTrue());
  }

  @Test
  @DisplayName("the IP tier's variable moves the IP tier and leaves the subject tier alone")
  void ipVariableMovesOnlyTheIpTier() {
    runner()
        .withPropertyValues(
            "RAMALS_RATE_LIMIT_CAPACITY=777", "RAMALS_RATE_LIMIT_REFILL_PER_SECOND=333")
        .run(
            context -> {
              RateLimitProperties properties = context.getBean(RateLimitProperties.class);
              assertThat(properties.getCapacity()).isEqualTo(777);
              assertThat(properties.getRefillPerSecond()).isEqualTo(333.0);
              assertThat(properties.getSubject().getCapacity())
                  .as("the per-learner tier must not follow the IP tier's variable")
                  .isEqualTo(120);
              assertThat(properties.getSubject().getRefillPerSecond())
                  .as("the per-learner refill must not follow the IP tier's variable")
                  .isEqualTo(60.0);
            });
  }

  @Test
  @DisplayName("the subject tier's variable cannot overwrite the IP tier")
  void subjectVariableCannotOverwriteTheIpTier() {
    // The failure this guards against is not hypothetical in shape: the two tiers have identically
    // named properties one level apart, so a mis-nested binding silently retunes the wrong one.
    // Here the subject values are set far above the IP tier's, which would be plainly visible if
    // they leaked upward.
    runner()
        .withPropertyValues(
            "RAMALS_RATE_LIMIT_SUBJECT_CAPACITY=9999",
            "RAMALS_RATE_LIMIT_SUBJECT_REFILL_PER_SECOND=8888")
        .run(
            context -> {
              RateLimitProperties properties = context.getBean(RateLimitProperties.class);
              assertThat(properties.getSubject().getCapacity()).isEqualTo(9999);
              assertThat(properties.getSubject().getRefillPerSecond()).isEqualTo(8888.0);
              assertThat(properties.getCapacity())
                  .as("the shared IP bucket must be untouched by a SUBJECT-named variable")
                  .isEqualTo(600);
              assertThat(properties.getRefillPerSecond())
                  .as("the shared IP refill must be untouched by a SUBJECT-named variable")
                  .isEqualTo(300.0);
            });
  }

  @Test
  @DisplayName("both tiers can be set at once, each to its own value")
  void bothTiersBindIndependently() {
    runner()
        .withPropertyValues(
            "RAMALS_RATE_LIMIT_CAPACITY=500",
            "RAMALS_RATE_LIMIT_REFILL_PER_SECOND=250",
            "RAMALS_RATE_LIMIT_SUBJECT_CAPACITY=50",
            "RAMALS_RATE_LIMIT_SUBJECT_REFILL_PER_SECOND=25")
        .run(
            context -> {
              RateLimitProperties properties = context.getBean(RateLimitProperties.class);
              assertThat(properties.getCapacity()).isEqualTo(500);
              assertThat(properties.getRefillPerSecond()).isEqualTo(250.0);
              assertThat(properties.getSubject().getCapacity()).isEqualTo(50);
              assertThat(properties.getSubject().getRefillPerSecond()).isEqualTo(25.0);
            });
  }

  @Test
  @DisplayName("the shipped defaults leave the shared bucket larger than one learner's")
  void theSharedBucketIsLargerThanOneLearners() {
    runner()
        .run(
            context -> {
              RateLimitProperties properties = context.getBean(RateLimitProperties.class);
              // Stated as a relationship so it survives retuning. A shared allowance smaller than
              // an individual's is incoherent: the office trips before any one person in it does,
              // which is precisely the state this fix removes.
              assertThat(properties.getCapacity())
                  .as("shared IP capacity vs one learner's")
                  .isGreaterThan(properties.getSubject().getCapacity());
              assertThat(properties.getRefillPerSecond())
                  .as("shared IP refill vs one learner's")
                  .isGreaterThan(properties.getSubject().getRefillPerSecond());
            });
  }
}
