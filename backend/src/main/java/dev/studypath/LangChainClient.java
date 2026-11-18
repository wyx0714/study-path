package dev.studypath;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class LangChainClient implements ChatModel {
    private final RestClient http;

    LangChainClient(String baseUrl, String token) {
        URI address = URI.create(baseUrl);
        if (!"http".equals(address.getScheme()) || !"127.0.0.1".equals(address.getHost()) || address.getPort() < 1
                || address.getRawUserInfo() != null || address.getRawQuery() != null || address.getRawFragment() != null
                || (!address.getPath().isEmpty() && !address.getPath().equals("/")) || token.isBlank()) {
            throw new IllegalArgumentException("LangChain must use a loopback HTTP address and a shared token");
        }
        var timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(2));
        timeouts.setReadTimeout(Duration.ofSeconds(22));
        http = RestClient.builder().baseUrl(baseUrl).requestFactory(timeouts)
                .defaultHeaders(headers -> headers.setBearerAuth(token)).build();
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        var messages = request.messages();
        if (messages.size() != 2 || !(messages.get(0) instanceof SystemMessage system)
                || !(messages.get(1) instanceof UserMessage user) || !user.hasSingleText()) {
            throw new IllegalArgumentException("Expected a system instruction and a text user message");
        }
        var response = http.post().uri("/generate").body(new Prompt(system.text(), user.singleText()))
                .retrieve().body(Result.class);
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new IllegalStateException("LangChain returned an empty result");
        }
        return ChatResponse.builder().aiMessage(AiMessage.from(response.content())).build();
    }

    private record Prompt(String instructions, String context) {}
    private record Result(String content) {}
}
