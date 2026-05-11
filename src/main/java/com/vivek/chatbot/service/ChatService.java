package com.vivek.chatbot.service;

import com.vivek.chatbot.dto.ChatDtos.*;
import com.vivek.chatbot.exception.ResourceNotFoundException;
import com.vivek.chatbot.model.ChatMessage;
import com.vivek.chatbot.model.ChatSession;
import com.vivek.chatbot.repository.ChatMessageRepository;
import com.vivek.chatbot.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Core business logic — orchestrates session creation, message processing, history retrieval.
 *
 * ── Flow for POST /chat (the main endpoint) ──────────────────────────────────
 *
 *   1. Validate sessionId exists in PostgreSQL
 *   2. If Redis cache is cold → warm it from PostgreSQL
 *   3. Append user message to Redis
 *   4. Read full context window from Redis
 *   5. Call OpenAI with full history (this is how "memory" works)
 *   6. Append AI reply to Redis
 *   7. Persist both messages to PostgreSQL
 *   8. Return response
 *
 * ── Interview explanation ────────────────────────────────────────────────────
 * "The service layer is the brain. It decides when to use Redis vs PostgreSQL,
 *  handles the cold-start scenario, and keeps both stores consistent."
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final SessionCacheService cacheService;
    private final OpenAiClient openAiClient;

    // ── Create Session ────────────────────────────────────────────────────────

    @Transactional
    public CreateSessionResponse createSession(CreateSessionRequest request) {
        String sessionId = UUID.randomUUID().toString();
        String userId = request.getUserId() != null ? request.getUserId() : "anonymous";

        ChatSession session = ChatSession.builder()
                .sessionId(sessionId)
                .userId(userId)
                .systemPrompt(request.getSystemPrompt())
                .build();

        sessionRepository.save(session);
        log.info("Session created | sessionId={} userId={}", sessionId, userId);

        return CreateSessionResponse.builder()
                .sessionId(sessionId)
                .userId(userId)
                .systemPrompt(request.getSystemPrompt())
                .createdAt(session.getCreatedAt())
                .message("Session ready. Send messages via POST /chat")
                .build();
    }

    // ── Send Message ──────────────────────────────────────────────────────────

    @Transactional
    public ChatResponse sendMessage(ChatRequest request) {
        String sessionId = request.getSessionId();

        // Step 1: Validate session exists
        ChatSession session = sessionRepository
                .findBySessionIdAndActiveTrue(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Session not found or expired: " + sessionId));

        // Step 2: Warm Redis cache if cold (e.g. after Redis restart)
        if (!cacheService.exists(sessionId) && session.getTotalMessages() > 0) {
            log.info("Cache cold — warming from PostgreSQL | session={}", sessionId);
            List<ChatMessage> dbHistory = messageRepository
                    .findBySession_SessionIdOrderByCreatedAtAsc(sessionId);
            cacheService.warmFromDatabase(sessionId, dbHistory);
        }

        // Step 3: Append user message to Redis
        cacheService.appendMessage(sessionId, ChatMessage.Role.user, request.getMessage());

        // Step 4: Read full context window from Redis
        List<SessionCacheService.MessageEntry> contextWindow = cacheService.getMessages(sessionId);
        log.debug("Context window | session={} size={}", sessionId, contextWindow.size());

        // Step 5: Call OpenAI with full conversation history
        //         (sending all messages is what gives the AI its "memory")
        String aiReply = openAiClient.chat(session.getSystemPrompt(), contextWindow);

        // Step 6: Append AI reply to Redis
        cacheService.appendMessage(sessionId, ChatMessage.Role.assistant, aiReply);

        // Step 7: Persist both messages to PostgreSQL
        persistToDatabase(session, request.getMessage(), aiReply);

        // Step 8: Update session metadata
        session.setTotalMessages(session.getTotalMessages() + 2);
        sessionRepository.save(session);

        log.info("Message processed | session={} historyLen={}", sessionId, session.getTotalMessages());

        return ChatResponse.builder()
                .sessionId(sessionId)
                .reply(aiReply)
                .historyLength(session.getTotalMessages())
                .timestamp(LocalDateTime.now())
                .build();
    }

    // ── Get History ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public HistoryResponse getHistory(String sessionId) {
        ChatSession session = sessionRepository
                .findBySessionIdAndActiveTrue(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Session not found: " + sessionId));

        List<ChatMessage> messages = messageRepository
                .findBySession_SessionIdOrderByCreatedAtAsc(sessionId);

        List<MessageDto> dtos = messages.stream()
                .map(m -> MessageDto.builder()
                        .role(m.getRole())
                        .content(m.getContent())
                        .createdAt(m.getCreatedAt())
                        .build())
                .toList();

        return HistoryResponse.builder()
                .sessionId(sessionId)
                .userId(session.getUserId())
                .messages(dtos)
                .totalMessages(dtos.size())
                .createdAt(session.getCreatedAt())
                .lastActivityAt(session.getLastActivityAt())
                .build();
    }

    // ── Delete Session ────────────────────────────────────────────────────────

    @Transactional
    public DeleteSessionResponse deleteSession(String sessionId) {
        ChatSession session = sessionRepository
                .findBySessionIdAndActiveTrue(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Session not found: " + sessionId));

        // Soft delete — keep history in DB, just mark inactive + evict Redis
        session.setActive(false);
        sessionRepository.save(session);
        cacheService.evict(sessionId);

        log.info("Session deleted | sessionId={}", sessionId);

        return DeleteSessionResponse.builder()
                .sessionId(sessionId)
                .status("DELETED")
                .deletedAt(LocalDateTime.now())
                .build();
    }

    // ── List Sessions ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CreateSessionResponse> getSessionsForUser(String userId) {
        return sessionRepository
                .findByUserIdAndActiveTrueOrderByLastActivityAtDesc(userId)
                .stream()
                .map(s -> CreateSessionResponse.builder()
                        .sessionId(s.getSessionId())
                        .userId(s.getUserId())
                        .createdAt(s.getCreatedAt())
                        .build())
                .toList();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void persistToDatabase(ChatSession session, String userMessage, String aiReply) {
        ChatMessage userMsg = ChatMessage.builder()
                .session(session)
                .role(ChatMessage.Role.user)
                .content(userMessage)
                .build();

        ChatMessage assistantMsg = ChatMessage.builder()
                .session(session)
                .role(ChatMessage.Role.assistant)
                .content(aiReply)
                .build();

        messageRepository.save(userMsg);
        messageRepository.save(assistantMsg);
    }
}