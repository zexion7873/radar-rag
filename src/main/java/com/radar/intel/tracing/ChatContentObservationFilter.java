package com.radar.intel.tracing;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Puts the prompt and the answer on chat model spans, where Langfuse reads them as the generation's
 * input and output. Spring AI 2.0.1 only logs content; without this the generation shows null.
 */
class ChatContentObservationFilter implements ObservationFilter {

    static final String INPUT = "langfuse.observation.input";
    static final String OUTPUT = "langfuse.observation.output";

    private final JsonMapper json;

    ChatContentObservationFilter(JsonMapper json) {
        this.json = json;
    }

    @Override
    public Observation.Context map(Observation.Context context) {
        if (!(context instanceof ChatModelObservationContext chat)) {
            return context;
        }
        List<Turn> messages = chat.getRequest().getInstructions().stream()
                .map(m -> new Turn(m.getMessageType().getValue(), Objects.toString(m.getText(), "")))
                .toList();
        chat.addHighCardinalityKeyValue(KeyValue.of(INPUT, json.writeValueAsString(messages)));
        ChatResponse response = chat.getResponse();
        if (response != null) {
            chat.addHighCardinalityKeyValue(KeyValue.of(OUTPUT, text(response)));
        }
        return chat;
    }

    /** A record keeps "role" before "content", so one prompt always serializes the same way. */
    private record Turn(String role, String content) {
    }

    /** Thinking blocks arrive as Generations with empty text (Opus 5.5 omits them), so joining is safe. */
    private static String text(ChatResponse response) {
        return response.getResults().stream()
                .map(Generation::getOutput)
                .map(Message::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
    }
}
