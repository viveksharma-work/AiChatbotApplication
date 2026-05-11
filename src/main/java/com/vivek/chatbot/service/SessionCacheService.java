package com.vivek.chatbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vivek.chatbot.model.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the active context window in Redis.
 *
 * ── WHY Redis? ──────────────────────────────────────────────────────────────
 * Every POST /chat call needs the full conversation history to send to OpenAI.
 * If we read from PostgreSQL every time → slow DB query on every single message.
 * Redis gives sub-millisecond reads and auto-expires inactive sessions via TTL.
 *
 * ── Redis key structure ─────────────────────────────────────────────────────
 *   chat:session:{sessionId}:messages → JSON array of {role, content} objects
 *
 * ── Interview explanation ────────────────────────────────────────────────────
 * "I used Redis as a write-through cache for the active context window.
 *  On every message, I append to Redis and async-persist to PostgreSQL.
 *  If Redis goes down or TTL expires, the service cold-starts from PostgreSQL."
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.session.ttl-minutes:30}")
    private long sessionTtlMinutes;

    @Value("${app.chat.max-context-messages:20}")
    private int maxContextMessages;

    private static final String KEY_PREFIX = "chat:session:";
    private static final String KEY_SUFFIX = ":messages";

    // ── Public methods ───────────────────────────────────────────────────────

    /**
     * Add a message to the Redis session.
     * Resets TTL on every write → sliding expiry window.
     */
    public void appendMessage(String sessionId, ChatMessage.Role role, String content) {
        String key = buildKey(sessionId);
        try {
            List<MessageEntry> messages = getMessages(sessionId);
            messages.add(new MessageEntry(role.name(), content));

            // Trim old messages to stay within context window limit
            if (messages.size() > maxContextMessages) {
                messages = messages.subList(messages.size() - maxContextMessages, messages.size());
            }

            String json = objectMapper.writeValueAsString(messages);
            redisTemplate.opsForValue().set(key, json, Duration.ofMinutes(sessionTtlMinutes));

            log.debug("Appended message to cache | session={} role={} contextSize={}",
                    sessionId, role, messages.size());

        } catch (Exception e) {
            log.error("Failed to cache message | session={}", sessionId, e);
            // Non-fatal: chat still works, just uses DB fallback
        }
    }

    /**
     * Get all messages for a session from Redis.
     * Returns empty list if cache is cold (caller should then warm from DB).
     */
    public List<MessageEntry> getMessages(String sessionId) {
        String key = buildKey(sessionId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) return new ArrayList<>();
            return objectMapper.readValue(json, new TypeReference<List<MessageEntry>>() {});
        } catch (Exception e) {
            log.warn("Cache read failed | session={} | msg={}", sessionId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Warm the cache from database messages.
     * Called when Redis is cold but PostgreSQL has the history.
     */
    public void warmFromDatabase(String sessionId, List<ChatMessage> dbMessages) {
        String key = buildKey(sessionId);
        try {
            List<MessageEntry> entries = dbMessages.stream()
                    .map(m -> new MessageEntry(m.getRole().name(), m.getContent()))
                    .toList();

            // Only cache the most recent N messages
            List<MessageEntry> context = entries.size() > maxContextMessages
                    ? entries.subList(entries.size() - maxContextMessages, entries.size())
                    : entries;

            String json = objectMapper.writeValueAsString(context);
            redisTemplate.opsForValue().set(key, json, Duration.ofMinutes(sessionTtlMinutes));

            log.info("Cache warmed from DB | session={} dbMessages={} contextMessages={}",
                    sessionId, dbMessages.size(), context.size());

        } catch (Exception e) {
            log.error("Cache warm failed | session={}", sessionId, e);
        }
    }

    /** Check if a session exists in Redis */
    public boolean exists(String sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(sessionId)));
    }

    /** Remove session from Redis (on delete / manual eviction) */
    public void evict(String sessionId) {
        redisTemplate.delete(buildKey(sessionId));
        log.info("Evicted session from cache | session={}", sessionId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String buildKey(String sessionId) {
        return KEY_PREFIX + sessionId + KEY_SUFFIX;
    }

    /**
     * Matches exactly what OpenAI expects in its messages[] array.
     * { "role": "user", "content": "Hello" }
     */
    public record MessageEntry(String role, String content) {}
}