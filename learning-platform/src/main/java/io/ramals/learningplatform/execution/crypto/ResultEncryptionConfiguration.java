package io.ramals.learningplatform.execution.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the environment-backed adapter as the one implementation of the port.
 *
 * <p>The bean is typed as {@link ResultEncryptionKeyProvider}, not as the adapter, so every
 * injection point depends on the port. That is what makes swapping in a KMS adapter later a change
 * to this class alone.
 *
 * <p>Registered unconditionally and harmless without configuration: with no keys configured the
 * provider throws on first use rather than starting the platform with a default key. Nothing calls
 * it yet -- no Contract B route is active and `V037` does not exist.
 */
@Configuration
@EnableConfigurationProperties(ResultEncryptionKeyProperties.class)
public class ResultEncryptionConfiguration {

  @Bean
  ResultEncryptionKeyProvider resultEncryptionKeyProvider(ResultEncryptionKeyProperties props) {
    return new EnvironmentResultEncryptionKeyProvider(props);
  }
}
