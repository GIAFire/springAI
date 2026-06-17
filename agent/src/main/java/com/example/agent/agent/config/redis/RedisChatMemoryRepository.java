package com.example.agent.agent.config.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String KEY_PREFIX = "ai:chat:memory:";
    private static final String CONVERSATION_SET_KEY = "ai:chat:memory:conversation-ids";

    private final RedisService redisService;

    /**
     * 不再依赖 Spring 注入，避免：
     * Could not autowire. No beans of 'ObjectMapper' type found.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Duration ttl = Duration.ofDays(7);

    public RedisChatMemoryRepository(RedisService redisService) {
        this.redisService = redisService;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> ids = redisService.getCacheSet(CONVERSATION_SET_KEY);

        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        return ids.stream().toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String key = key(conversationId);

        String json = redisService.getCacheObject(key);

        if (json == null || json.isBlank()) {
            return List.of();
        }

        try {
            List<StoredMessage> storedMessages = objectMapper.readValue(
                    json,
                    new TypeReference<List<StoredMessage>>() {
                    }
            );

            return storedMessages.stream()
                    .map(this::toMessage)
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to read chat memory from Redis. conversationId=" + conversationId,
                    e
            );
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String key = key(conversationId);

        try {
            List<StoredMessage> storedMessages = messages.stream()
                    .map(this::fromMessage)
                    .toList();

            String json = objectMapper.writeValueAsString(storedMessages);

            redisService.setCacheObject(
                    key,
                    json,
                    ttl.toSeconds(),
                    TimeUnit.SECONDS
            );

            redisService.addCacheSetValue(CONVERSATION_SET_KEY, conversationId);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to save chat memory to Redis. conversationId=" + conversationId,
                    e
            );
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redisService.deleteObject(key(conversationId));
        redisService.removeCacheSetValue(CONVERSATION_SET_KEY, conversationId);
    }

    private String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    private StoredMessage fromMessage(Message message) {
        return new StoredMessage(
                message.getMessageType(),
                message.getText(),
                message.getMetadata()
        );
    }

    private Message toMessage(StoredMessage storedMessage) {
        MessageType type = storedMessage.type();
        String text = storedMessage.text();

        if (type == MessageType.USER) {
            return new UserMessage(text);
        }

        if (type == MessageType.ASSISTANT) {
            return new AssistantMessage(text);
        }

        if (type == MessageType.SYSTEM) {
            return new SystemMessage(text);
        }

        throw new IllegalArgumentException("Unsupported message type: " + type);
    }

    private record StoredMessage(
            MessageType type,
            String text,
            Map<String, Object> metadata
    ) {
    }
}