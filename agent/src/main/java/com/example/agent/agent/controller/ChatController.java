package com.example.agent.agent.controller;

import com.example.agent.agent.rag.advisors.TestAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.UncheckedIOException;

@RestController
@RequestMapping("/chat")
public class ChatController {

    @Autowired
    @Qualifier("userAgentClient")
    private ChatClient userAgentClient;
    @Autowired
    @Qualifier("orderAgentClient")
    private ChatClient orderAgentClient;

    @GetMapping("/chat")
    public String chat(@RequestParam String conversationId,
                       @RequestParam String msg,
                       @RequestParam(defaultValue = "user") String agent) {
        ChatClient agentClient = "order".equalsIgnoreCase(agent) ? orderAgentClient : userAgentClient;
        return agentClient.prompt()
                .user(msg)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .advisors(new TestAdvisor())
                .call()
                .content();
    }

    @GetMapping(value = "/streamChat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestParam String msg) {
        return userAgentClient.prompt()
                .user(msg)
                .stream()
                .content();
    }

    @GetMapping(value = "/chatSseEmitter", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatSseEmitter(@RequestParam String conversationId, @RequestParam String msg) {
        SseEmitter emitter = new SseEmitter(0L);

        userAgentClient.prompt("")
                .user(msg)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content()
                .subscribe(
                        content -> sendMessage(emitter, content),
                        emitter::completeWithError,
                        emitter::complete
                );
        return emitter;
    }
    private void sendMessage(SseEmitter emitter, String content) {
        try {
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
