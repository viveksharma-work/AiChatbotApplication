package com.vivek.chatbot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP client that calls the OpenAI Chat Completions API.
 *
 * ── Why no SDK? ─────────────────────────────────────────────────────────────
 * We use Spring's WebClient (which you already know from Spring Boot) to call
 * OpenAI as a plain REST API. No special AI library needed.
 * This is easy to explain: "I called OpenAI the same way I'd call any external
 * REST API — using WebClient, sending a JSON body, reading a JSON response."
 *
 * ── How memory works (important interview point) ─────────────────────────────
 * OpenAI is STATELESS — it remembers nothing between requests.
 * We fake memory by sending the ENTIRE conversation history on every request
 * as the messages[] array. The AI sees the whole chat and responds in context.
 * Our Redis cache stores this history so we can rebuild it fast.
 *
 * ── API request structure ────────────────────────────────────────────────────
 * POST https://api.openai.com/v1/chat/completions
 * {
 *   "model": "gpt-4o-mini",
 *   "messages": [
 *     { "role": "system",    "content": "You are a helpful assistant." },
 *     { "role": "user",      "content": "Hello!" },
 *     { "role": "assistant", "content": "Hi! How can I help?" },
 *     { "role": "user",      "content": "What did I just say?" }  ← new message
 *   ]
 * }
 */
@Service
@Slf4j
public class OpenAiClient {

    private final WebClient webClient;

    @Value("${app.openai.model}")
    private String model;

    @Value("${app.openai.max-tokens}")
    private int maxTokens;

    @Value("${app.openai.system-prompt}")
    private String defaultSystemPrompt;

    public OpenAiClient(
            @Value("${app.openai.base-url}") String baseUrl,
            @Value("${app.openai.api-key}") String apiKey) {

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Send the full conversation history to OpenAI and get a reply.
     *
     * @param systemPrompt  Behaviour instructions for the AI (set once per session)
     * @param history       Full conversation so far from Redis cache
     * @return              The AI's reply text
     */
    public String chat(String systemPrompt, List<SessionCacheService.MessageEntry> history) {

        // Build messages array: system prompt + full history
        List<Map<String, String>> messages = buildMessages(systemPrompt, history);

        // Request body
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "messages", messages
        );

        try {
            log.info("Calling OpenAI | model={} | contextMessages={}", model, history.size());
            long start = System.currentTimeMillis();

            OpenAiResponse response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(OpenAiResponse.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            log.info("OpenAI responded | elapsed={}ms", System.currentTimeMillis() - start);

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new OpenAiException("Empty response from OpenAI");
            }

            return response.choices().get(0).message().content();

        } catch (WebClientResponseException e) {
            log.error("OpenAI API error | status={} | body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new OpenAiException("OpenAI returned error: " + e.getStatusCode());
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Builds the messages[] array OpenAI expects.
     *
     * Structure:
     *   [0] system message  → sets AI behaviour
     *   [1..N] conversation history → this IS the memory
     */
    private List<Map<String, String>> buildMessages(
            String sessionSystemPrompt,
            List<SessionCacheService.MessageEntry> history) {

        List<Map<String, String>> messages = new ArrayList<>();

        // System prompt first
        String prompt = (sessionSystemPrompt != null && !sessionSystemPrompt.isBlank())
                ? sessionSystemPrompt
                : defaultSystemPrompt;

        messages.add(Map.of("role", "system", "content", prompt));

        // Full conversation history after
        history.forEach(entry ->
                messages.add(Map.of("role", entry.role(), "content", entry.content())));

        return messages;
    }

    // ── OpenAI response DTOs (maps directly to JSON response) ────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpenAiResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String role, String content) {}

    // ── Custom exception ─────────────────────────────────────────────────────

    public static class OpenAiException extends RuntimeException {
        public OpenAiException(String msg) { super(msg); }
    }
}