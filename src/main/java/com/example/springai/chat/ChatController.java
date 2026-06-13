package com.example.springai.chat;

import com.example.springai.tools.MallAdminRoleTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

@RestController
@RequestMapping("/chat")
public class ChatController {

    @Autowired
    private ChatClient chatClient;
    @Autowired
    private MallAdminRoleTools mallAdminRoleTools;

    @GetMapping("/chat")
    public String chat(@RequestParam String conversationId,@RequestParam String msg) {
        return chatClient.prompt()
                .user(msg)
                .tools(mallAdminRoleTools)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    @GetMapping(value = "/streamChat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestParam String msg) {
        return chatClient.prompt()
                .user(msg)
                .stream()
                .content();
    }

    @GetMapping(value = "/chatSseEmitter", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatSseEmitter(@RequestParam String conversationId, @RequestParam String msg) {
        SseEmitter emitter = new SseEmitter(0L);

        chatClient.prompt("")
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
