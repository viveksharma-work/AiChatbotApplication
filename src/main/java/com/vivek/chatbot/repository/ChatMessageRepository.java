package com.vivek.chatbot.repository;

import com.vivek.chatbot.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {


    List<ChatMessage> findBySession_SessionIdOrderByCreatedAtAsc(String sessionId);

    long countBySession_SessionId(String sessionId);
}