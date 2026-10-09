package com.radar.intel.ask;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

@Configuration
class AnthropicConfig {

    /**
     * Replaces the auto-configured model: Spring AI 1.0.0's AnthropicChatProperties seeds a default
     * temperature of 0.8 that YAML cannot unset, and Opus 5.5 answers any sampling parameter with a 400.
     */
    @Bean
    AnthropicChatModel anthropicChatModel(AnthropicApi anthropicApi, AnthropicChatProperties chatProperties,
            RetryTemplate retryTemplate, ToolCallingManager toolCallingManager,
            ObjectProvider<ObservationRegistry> observationRegistry) {
        AnthropicChatOptions configured = chatProperties.getOptions();
        return AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model(configured.getModel())
                        .maxTokens(configured.getMaxTokens())
                        .build())
                .toolCallingManager(toolCallingManager)
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .build();
    }
}
