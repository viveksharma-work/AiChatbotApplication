package com.vivek.chatbot.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vivek.chatbot.model.ChatMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

public class ChatDtos {


    @Getter @Setter @NoArgsConstructor
    public static class CreateSessionRequest {
        private String userId;

        @Size(max = 500)
        private String systemPrompt;
    }

    @Getter @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CreateSessionResponse {
        private String sessionId;
        private String userId;
        private String systemPrompt;
        private LocalDateTime createdAt;
        private String message;
    }


    @Getter @Setter @NoArgsConstructor
    public static class ChatRequest {
        @NotBlank(message = "sessionId is required")
        private String sessionId;

        @NotBlank(message = "message cannot be blank")
        @Size(max = 4000, message = "message too long")
        private String message;
    }

    @Getter @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChatResponse {
        private String sessionId;
        private String reply;
        private int historyLength;
        private LocalDateTime timestamp;
    }


    @Getter @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HistoryResponse {
        private String sessionId;
        private String userId;
        private List<MessageDto> messages;
        private int totalMessages;
        private LocalDateTime createdAt;
        private LocalDateTime lastActivityAt;
    }

    @Getter @Builder
    public static class MessageDto {
        private ChatMessage.Role role;
        private String content;
        private LocalDateTime createdAt;
    }


    @Getter @Builder
    public static class DeleteSessionResponse {
        private String sessionId;
        private String status;
        private LocalDateTime deletedAt;
    }


    @Getter @Builder
    public static class ErrorResponse {
        private int status;
        private String error;
        private String message;
        private LocalDateTime timestamp;
    }
}