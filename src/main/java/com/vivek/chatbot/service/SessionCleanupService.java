package com.vivek.chatbot.service;

import com.vivek.chatbot.model.ChatSession;
import com.vivek.chatbot.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Background job that deactivates expired sessions in PostgreSQL.
 *
 * Redis handles its own expiry via TTL automatically.
 * This job handles the PostgreSQL side — soft-deleting sessions
 * that haven't been used for longer than the configured TTL.
 *
 * Runs every 15 minutes.
 *
 * Interview point: "I used Spring's @Scheduled for periodic cleanup.
 * Redis expires sessions automatically via TTL, but I also needed to
 * mark them inactive in PostgreSQL to keep the DB clean."
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionCleanupService {

    private final ChatSessionRepository sessionRepository;
    private final SessionCacheService cacheService;

    @Value("${app.session.ttl-minutes:30}")
    private long sessionTtlMinutes;

    @Scheduled(fixedDelay = 15 * 60 * 1000) // every 15 minutes
    @Transactional
    public void cleanupExpiredSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(sessionTtlMinutes);
        List<ChatSession> expired = sessionRepository.findExpiredSessions(cutoff);

        if (expired.isEmpty()) {
            log.debug("Cleanup: no expired sessions");
            return;
        }

        log.info("Cleanup: deactivating {} expired sessions", expired.size());
        expired.forEach(s -> {
            s.setActive(false);
            cacheService.evict(s.getSessionId());
        });

        sessionRepository.saveAll(expired);
        log.info("Cleanup complete");
    }
}