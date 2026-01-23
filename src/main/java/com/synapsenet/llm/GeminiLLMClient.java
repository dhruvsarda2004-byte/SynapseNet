package com.synapsenet.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Component
@Profile("gemini")  // when profile = gemini then only real API else the MockLLMClient is used
public class GeminiLLMClient implements LLMClient {

    private final WebClient webClient;

    @Value("${gemini.api.key}")
    private String apiKey;

    public GeminiLLMClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    @Override
    public String generate(String prompt) {

        String url =
            "https://generativelanguage.googleapis.com/v1beta/models/"
            + "gemini-1.5-flash:generateContent?key=" + apiKey;

        Map<String, Object> body = Map.of(
            "contents", new Object[]{
                Map.of(
                    "parts", new Object[]{
                        Map.of("text", prompt)
                    }
                )
            }
        );

        Map<?, ?> response = webClient
                .post()
                .uri(url)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(); // BLOCKING ON PURPOSE (for now)

        return extractText(response);
    }

    private String extractText(Map<?, ?> response) {
        try {
            var candidates = (Iterable<?>) response.get("candidates");
            var first = (Map<?, ?>) candidates.iterator().next();
            var content = (Map<?, ?>) first.get("content");
            var parts = (Iterable<?>) content.get("parts");
            var part = (Map<?, ?>) parts.iterator().next();
            return part.get("text").toString();
        } catch (Exception e) {
            return "[Gemini error: could not parse response]";
        }
    }
}
