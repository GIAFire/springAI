package com.example.springai.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DocumentIngestionService {

    private static final String DEFAULT_SOURCE = "manual";

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;

    public DocumentIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.textSplitter = new TokenTextSplitter();
    }

    public IngestionResult ingestText(String text, String source) {
        String normalizedText = normalizeText(text);
        String normalizedSource = normalizeSource(source);

        Document document = new Document(normalizedText, Map.of(
                "source", normalizedSource,
                "sourceType", "text",
                "ingestedAt", Instant.now().toString()
        ));

        return splitAndStore(List.of(document), normalizedSource);
    }

    public IngestionResult ingestFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String fileName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename()
                : "uploaded-document";
        String text = normalizeText(new String(file.getBytes(), StandardCharsets.UTF_8));

        Document document = new Document(text, Map.of(
                "source", fileName,
                "sourceType", "file",
                "fileName", fileName,
                "contentType", file.getContentType() == null ? "unknown" : file.getContentType(),
                "ingestedAt", Instant.now().toString()
        ));

        return splitAndStore(List.of(document), fileName);
    }

    private IngestionResult splitAndStore(List<Document> documents, String source) {
        List<Document> chunks = textSplitter.apply(documents);
        List<Document> indexedChunks = addChunkMetadata(chunks);
        vectorStore.add(indexedChunks);
        return new IngestionResult(source, documents.size(), indexedChunks.size());
    }

    private List<Document> addChunkMetadata(List<Document> chunks) {
        List<Document> indexedChunks = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("chunkIndex", i);
            metadata.put("chunkCount", chunks.size());
            indexedChunks.add(new Document(chunk.getText(), metadata));
        }

        return indexedChunks;
    }

    private String normalizeText(String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        return text.trim();
    }

    private String normalizeSource(String source) {
        return StringUtils.hasText(source) ? source.trim() : DEFAULT_SOURCE;
    }

    public record IngestionResult(String source, int documentCount, int chunkCount) {
    }
}
