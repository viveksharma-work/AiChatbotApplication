Add your OpenAI API key to application.properties
Run docker-compose up -d postgres redis → starts your DB and cache
Run ./mvnw spring-boot:run → starts your Spring Boot app
Open Postman → hit the 3 endpoints in order

The memory demo that'll impress interviewers:

Create a session:
Body → raw → JSON:
json{
  "userId": "",
  "systemPrompt": "You are a helpful assistant."
}
*Copy the sessionId from the response.

Send a message
POST http://localhost:8080/chat
Body:
{
  "sessionId": "paste-the-session-id-you-got-above",
  "message": "My name is abcd"
}

Send: "What is my name?"
AI replies: "Your name is abcd"

#In-case of error's
There are some common errors
In application.properties:
paste:logging.level.org.springframework.web.reactive=DEBUG

Test your key directly in Postman
Skip your app entirely and hit OpenAI directly:
POST https://api.openai.com/v1/chat/completions
Headers:
Authorization: Bearer sk-your-key-here
Content-Type: application/json
Body:
json{
  "model": "gpt-4o-mini",
  "max_tokens": 100,
  "messages": [
    { "role": "user", "content": "Say hello" }
  ]
}
If this returns 401 → wrong key. If 429 → no credits. If 200 → key works and the bug is elsewhere.
