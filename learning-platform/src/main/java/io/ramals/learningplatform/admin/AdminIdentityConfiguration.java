package io.ramals.learningplatform.admin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AdminIdentityProperties.class)
public class AdminIdentityConfiguration {
}
