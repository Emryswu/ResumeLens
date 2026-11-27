package com.arthur.jdragresume.ai;

import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalChatGptModelsTests {
    private final AiProperties properties = new AiProperties();

    private LocalChatGptModels models(long owner, String python, String address) {
        return new LocalChatGptModels(new ObjectMapper(), properties, owner, python,
                "unused.py", address);
    }

    @Test
    void onlyExplicitLoopbackOwnerMayUseLocalSubscription() {
        var service = models(42, "unused-python", "127.0.0.1");
        assertThat(service.enabled(42)).isTrue();
        assertThat(service.catalog(41).enabled()).isFalse();
        assertThatThrownBy(() -> service.client(41, "gpt-test")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.select(41, "gpt-test")).isInstanceOf(BusinessException.class);
        assertThat(models(42, "unused-python", "0.0.0.0").enabled(42)).isFalse();
        assertThat(models(42, "", "127.0.0.1").enabled(42)).isFalse();
        assertThat(models(0, "unused-python", "127.0.0.1").enabled(42)).isFalse();
        properties.setMockEnabled(true);
        assertThat(service.enabled(42)).isFalse();
    }

    @Test
    void rejectsInvalidModelBeforeStartingHelper() {
        var service = models(42, "unused-python", "127.0.0.1");
        assertThatThrownBy(() -> service.client(42, "--help")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.select(42, "model with spaces")).isInstanceOf(BusinessException.class);
    }
}
