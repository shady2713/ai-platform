package com.basicframework.framework.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.ai.core.model.ModelCapability;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class AiPropertiesTest {

    @Test
    void shouldDefaultToDisabledWithEmptyCapabilities() {
        AiProperties properties = new AiProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getCapabilities()).isEmpty();
        assertThat(properties.isCapabilityDeclaredWhenEnabled()).isTrue();
    }

    @Test
    void shouldDeclareCapabilityRequirementOnlyWhenEnabled() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        assertThat(properties.isCapabilityDeclaredWhenEnabled()).isFalse();

        properties.setCapabilities(EnumSet.of(ModelCapability.TEXT));
        assertThat(properties.isCapabilityDeclaredWhenEnabled()).isTrue();
        assertThat(properties.getCapabilities()).containsExactly(ModelCapability.TEXT);
    }
}
