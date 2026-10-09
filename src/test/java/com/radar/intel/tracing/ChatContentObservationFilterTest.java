package com.radar.intel.tracing;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatContentObservationFilterTest {

    private final ChatContentObservationFilter filter = new ChatContentObservationFilter(new JsonMapper());

    @Test
    void putsThePromptAndTheJoinedAnswerOnTheChatModelSpan() {
        ChatModelObservationContext context = ChatModelObservationContext.builder()
                .prompt(new Prompt(List.of(new SystemMessage("be brief"), new UserMessage("which repo?"))))
                .provider("anthropic")
                .build();
        context.setResponse(new ChatResponse(List.of(
                new Generation(AssistantMessage.builder().content("").build()),
                new Generation(AssistantMessage.builder().content("Try ").build()),
                new Generation(AssistantMessage.builder().content("o/repo.").build()))));

        filter.map(context);

        assertThat(context.getHighCardinalityKeyValue(ChatContentObservationFilter.INPUT).getValue())
                .isEqualTo("[{\"role\":\"system\",\"content\":\"be brief\"},"
                        + "{\"role\":\"user\",\"content\":\"which repo?\"}]");
        assertThat(context.getHighCardinalityKeyValue(ChatContentObservationFilter.OUTPUT).getValue())
                .isEqualTo("Try o/repo.");
    }

    @Test
    void leavesOtherObservationsAlone() {
        Observation.Context context = new Observation.Context();

        filter.map(context);

        assertThat(context.getHighCardinalityKeyValues()).isEmpty();
    }

    @Test
    void anUnfinishedCallCarriesOnlyTheInput() {
        ChatModelObservationContext context = ChatModelObservationContext.builder()
                .prompt(new Prompt("q"))
                .provider("anthropic")
                .build();

        filter.map(context);

        assertThat(context.getHighCardinalityKeyValues()).extracting(KeyValue::getKey)
                .containsExactly(ChatContentObservationFilter.INPUT);
    }
}
