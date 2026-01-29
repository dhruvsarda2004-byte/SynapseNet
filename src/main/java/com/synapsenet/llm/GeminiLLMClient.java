package com.synapsenet.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Component
@Profile("gemini")
public class GeminiLLMClient implements LLMClient {

    private static final Logger log =
            LoggerFactory.getLogger(GeminiLLMClient.class);

    /** Retry configuration (hardcoded for research determinism) */
    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 500;
    private static final long MAX_JITTER_MS = 250;

    private final WebClient webClient;
    private final Random jitterRandom = new Random();

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.model}")
    private String model;

    @Value("${gemini.api.base-url}")
    private String baseUrl;

    public GeminiLLMClient(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    @Override
    public String generate(String prompt) {

        log.info("Using Gemini | model={} | keyHash={}",
                model, apiKey.hashCode());

        Map<String, Object> body = Map.of(
            "contents", List.of(
                Map.of(
                    "parts", List.of(
                        Map.of("text", prompt)
                    )
                )
            )
        );

        int attempt = 0;
        int retryCount = 0;

        while (true) {
            try {
                attempt++;

                log.debug("[Gemini] Attempt {} sending request", attempt);

                Map<?, ?> response = webClient
                        .post()
                        .uri(baseUrl + "/models/" + model + ":generateContent?key=" + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .timeout(Duration.ofSeconds(15))
                        .block();

                retryCount = attempt - 1;

                log.info(
                    "[Gemini] Call succeeded | retries={}",
                    retryCount
                );

                return extractText(response);

            } catch (Exception ex) {

                if (!isRetryable(ex) || attempt > MAX_RETRIES) {

                    retryCount = attempt - 1;

                    log.error(
                        "[Gemini] Final failure | attempts={} | retries={}",
                        attempt,
                        retryCount,
                        ex
                    );

                    throw ex;
                }

                long backoff = computeBackoff(attempt);

                log.warn(
                    "[Gemini] Transient failure on attempt {}. Retrying after {} ms. Cause: {}",
                    attempt,
                    backoff,
                    rootMessage(ex)
                );

                sleep(backoff);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<?, ?> response) {
        try {
            var candidates = (List<Map<String, Object>>) response.get("candidates");
            var content = (Map<String, Object>) candidates.get(0).get("content");
            var parts = (List<Map<String, Object>>) content.get("parts");
            return parts.get(0).get("text").toString();
        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", response, e);
            throw new IllegalStateException("Malformed Gemini response", e);
        }
    }

    /** Retry only transient failures (Spring-version safe) */
    private boolean isRetryable(Exception ex) {
        return ex instanceof WebClientResponseException.ServiceUnavailable   // 503
            || ex instanceof WebClientResponseException.TooManyRequests      // 429
            || ex instanceof IOException
            || ex.getCause() instanceof IOException;
    }

    /** Exponential backoff with jitter */
    private long computeBackoff(int attempt) {
        long exponential = BASE_BACKOFF_MS * (1L << (attempt - 1));
        long jitter = jitterRandom.nextLong(MAX_JITTER_MS + 1);
        return exponential + jitter;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
