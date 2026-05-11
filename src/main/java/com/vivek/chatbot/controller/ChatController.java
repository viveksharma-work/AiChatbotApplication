package com.vivek.chatbot.controller;

import com.vivek.chatbot.dto.ChatDtos.*;
import com.vivek.chatbot.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/session")
    public ResponseEntity<CreateSessionResponse> createSession(
            @RequestBody(required = false) CreateSessionRequest request) {

        if (request == null) request = new CreateSessionRequest();
        log.info("Creating session | userId={}", request.getUserId());

        CreateSessionResponse response = chatService.createSession(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping
    public ResponseEntity<ChatResponse> sendMessage(@Valid @RequestBody ChatRequest request) {
        log.info("Message received | session={}", request.getSessionId());
        ChatResponse response = chatService.sendMessage(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history/{sessionId}")
    public ResponseEntity<HistoryResponse> getHistory(@PathVariable String sessionId) {
        log.info("Fetching history | session={}", sessionId);
        HistoryResponse response = chatService.getHistory(sessionId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<DeleteSessionResponse> deleteSession(@PathVariable String sessionId) {
        log.info("Deleting session | session={}", sessionId);
        DeleteSessionResponse response = chatService.deleteSession(sessionId);
        return ResponseEntity.ok(response);
    }


    @GetMapping("/sessions/{userId}")
    public ResponseEntity<List<CreateSessionResponse>> getUserSessions(@PathVariable String userId) {
        List<CreateSessionResponse> sessions = chatService.getSessionsForUser(userId);
        return ResponseEntity.ok(sessions);
    }
}