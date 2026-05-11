package com.vivek.chatbot.repository;

import com.vivek.chatbot.model.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {


    Optional<ChatSession> findBySessionIdAndActiveTrue(String sessionId);


    List<ChatSession> findByUserIdAndActiveTrueOrderByLastActivityAtDesc(String userId);


    @Query("SELECT s FROM ChatSession s WHERE s.active = true AND s.lastActivityAt < :cutoff")
    List<ChatSession> findExpiredSessions(@Param("cutoff") LocalDateTime cutoff);
}