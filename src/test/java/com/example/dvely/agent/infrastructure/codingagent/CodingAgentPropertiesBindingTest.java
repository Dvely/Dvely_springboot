package com.example.dvely.agent.infrastructure.codingagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pins that the {@code qeploy.coding-agent} yml block actually binds. Context loading alone would
 * not catch a mistyped key: an unbound property silently keeps the field default, so a deployment
 * could set a 30-minute timeout and quietly get 10.
 */
@SpringBootTest
class CodingAgentPropertiesBindingTest {

    @Autowired
    private CodingAgentProperties properties;

    @Test
    void bindsTheImageFromConfiguration() {
        assertThat(properties.getImage()).isEqualTo("qeploy/coding-agent:local");
    }

    @Test
    void parsesTheDurationShorthandRatherThanTreatingItAsMilliseconds() {
        // "10m" in yml must become ten minutes; a raw-number reading would give 10 ms and every
        // run would look like an instant timeout.
        assertThat(properties.getTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.getProvisionRetryDelay()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void bindsTheWorkspaceMountPathAndProvisionAttempts() {
        assertThat(properties.getWorkspaceMountPath()).isEqualTo("/workspace");
        assertThat(properties.getMaxProvisionAttempts()).isEqualTo(3);
    }
}
