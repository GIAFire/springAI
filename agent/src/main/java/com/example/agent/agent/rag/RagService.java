package com.example.agent.agent.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final String RAG_SYSTEM_PROMPT = """
            你是一个企业知识库问答助手。
            请严格基于【参考资料】回答用户问题。
            如果参考资料不足以回答，请回答：资料中没有提到。
            不要编造来源、数字、链接或事实。
            回答末尾请列出你实际使用到的参考来源。
            """;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public RagService(VectorStore vectorStore, ChatClient.Builder builder) {
        this.vectorStore = vectorStore;
        this.chatClient = builder.build();
    }

    public RagAnswer ask(String question) {
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("问题不能为空");
        }

        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(5)
                .build());

        if (documents == null || documents.isEmpty()) {
            return new RagAnswer("资料中没有提到。", List.of());
        }

        String context = buildContext(documents);
        String answer = chatClient.prompt()
                .system(RAG_SYSTEM_PROMPT)
                .user("""
                        【参考资料】
                        %s

                        【用户问题】
                        %s
                        """.formatted(context, question.trim()))
                .call()
                .content();

        return new RagAnswer(answer, documents.stream()
                .map(RagSource::from)
                .toList());
    }

    private String buildContext(List<Document> documents) {
        return documents.stream()
                .map(this::formatDocument)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private String formatDocument(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        Object source = metadata.getOrDefault("source", "unknown");
        Object chunkIndex = metadata.getOrDefault("chunkIndex", "unknown");

        return """
                来源：%s
                分片：%s
                内容：
                %s
                """.formatted(source, chunkIndex, document.getText());
    }

    public record RagAnswer(String answer, List<RagSource> sources) {
    }

    public record RagSource(String source, Object chunkIndex, Double score, String preview) {

        static RagSource from(Document document) {
            Map<String, Object> metadata = document.getMetadata();
            String text = document.getText() == null ? "" : document.getText();
            String preview = text.length() > 160 ? text.substring(0, 160) + "..." : text;

            return new RagSource(
                    String.valueOf(metadata.getOrDefault("source", "unknown")),
                    metadata.getOrDefault("chunkIndex", "unknown"),
                    document.getScore(),
                    preview
            );
        }
    }
}
