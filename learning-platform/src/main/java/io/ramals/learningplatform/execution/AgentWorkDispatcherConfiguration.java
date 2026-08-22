package io.ramals.learningplatform.execution;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(AgentWorkDispatcherProperties.class)
class AgentWorkDispatcherConfiguration {
}
