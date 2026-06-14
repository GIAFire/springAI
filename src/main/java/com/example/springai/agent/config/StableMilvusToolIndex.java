package com.example.springai.agent.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.util.CollectionUtils;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 基于 Milvus 的工具索引实现。
 *
 * <p>这里把工具定义当作 Agent 级别的全局元数据，而不是每个会话都重新生成的一份临时数据。
 * Spring AI 默认的 VectorToolIndex 会为工具文档生成随机 ID，并且只把这些 ID 缓存在 JVM 内存中。
 * 如果底层使用持久化 Milvus collection，新会话或应用重启后就容易重复写入相同工具向量。
 * 这个实现使用稳定文档 ID 和描述 hash，工具描述没有变化时不会重新生成 embedding。</p>
 */
public class StableMilvusToolIndex implements ToolIndex, Closeable {

    private static final Log logger = LogFactory.getLog(StableMilvusToolIndex.class);

    private static final String METADATA_TOOL_ID = "toolId";

    private static final String METADATA_TOOL_NAME = "toolName";

    private static final String METADATA_TOOL_DESCRIPTION = "toolDescription";

    private static final String METADATA_DESCRIPTION_HASH = "descriptionHash";

    private static final int DEFAULT_MAX_RESULTS = 10;

    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.2;

    private final VectorStore vectorStore;

    private final MilvusServiceClient milvusClient;

    private final String databaseName;

    private final String collectionName;

    private final String idFieldName;

    private final String metadataFieldName;

    private final String toolId;

    private final Map<String, String> storedDescriptionHashes = new HashMap<>();

    private boolean loadedStoredState;

    public StableMilvusToolIndex(VectorStore vectorStore, MilvusServiceClient milvusClient, String databaseName,
                                 String collectionName, String idFieldName, String metadataFieldName,
                                 String toolId) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore must not be null");
        this.milvusClient = Objects.requireNonNull(milvusClient, "milvusClient must not be null");
        this.databaseName = Objects.requireNonNull(databaseName, "databaseName must not be null");
        this.collectionName = Objects.requireNonNull(collectionName, "collectionName must not be null");
        this.idFieldName = Objects.requireNonNull(idFieldName, "idFieldName must not be null");
        this.metadataFieldName = Objects.requireNonNull(metadataFieldName, "metadataFieldName must not be null");
        this.toolId = Objects.requireNonNull(toolId, "toolId must not be null");
    }

    @Override
    public void clearIndex(String sessionId) {
        // 工具定义属于当前 Agent，不属于某个具体会话。
        // ToolSearchAdvisor 清理会话缓存时，不应该删除 Milvus 中持久化的工具元数据。
    }

    @Override
    public void indexTool(String sessionId, ToolReference toolReference) {
        indexTools(sessionId, List.of(toolReference));
    }

    @Override
    public synchronized void indexTools(String sessionId, List<ToolReference> toolReferences) {
        if (CollectionUtils.isEmpty(toolReferences)) {
            return;
        }

        // 启动后第一次索引时，从 Milvus 读取已有 hash，避免重启后重复写入未变化的工具。
        loadStoredStateIfNecessary();

        Set<String> desiredIds = new HashSet<>();
        List<Document> changedDocuments = new ArrayList<>();
        List<String> changedDocumentIds = new ArrayList<>();

        for (ToolReference toolReference : toolReferences) {
            String documentId = documentId(toolReference.toolName());
            String descriptionHash = descriptionHash(toolReference);
            desiredIds.add(documentId);

            if (descriptionHash.equals(storedDescriptionHashes.get(documentId))) {
                continue;
            }

            changedDocumentIds.add(documentId);
            changedDocuments.add(Document.builder()
                    .id(documentId)
                    .text(toolReference.summary())
                    .metadata(Map.of(
                            // toolId 用于隔离不同 Agent 的工具集，即使共用 collection 也不会互相检索到。
                            METADATA_TOOL_ID, toolId,
                            METADATA_TOOL_NAME, toolReference.toolName(),
                            METADATA_TOOL_DESCRIPTION, toolReference.summary(),
                            METADATA_DESCRIPTION_HASH, descriptionHash
                    ))
                    .build());
        }

        List<String> staleDocumentIds = storedDescriptionHashes.keySet().stream()
                .filter(id -> !desiredIds.contains(id))
                .toList();

        if (!changedDocumentIds.isEmpty()) {
            // Spring AI 的 Milvus VectorStore 默认不是 upsert；先按稳定 ID 删除，再插入新向量。
            vectorStore.delete(changedDocumentIds);
            vectorStore.add(changedDocuments);
            for (int i = 0; i < changedDocumentIds.size(); i++) {
                storedDescriptionHashes.put(changedDocumentIds.get(i),
                        changedDocuments.get(i).getMetadata().get(METADATA_DESCRIPTION_HASH).toString());
            }
        }

        if (!staleDocumentIds.isEmpty()) {
            vectorStore.delete(staleDocumentIds);
            staleDocumentIds.forEach(storedDescriptionHashes::remove);
        }

        if (logger.isInfoEnabled() && (!changedDocumentIds.isEmpty() || !staleDocumentIds.isEmpty())) {
            logger.info("Tool index synchronized: upserted=%d, removed=%d, toolId=%s"
                    .formatted(changedDocumentIds.size(), staleDocumentIds.size(), toolId));
        }
    }

    @Override
    public ToolSearchResponse search(ToolSearchRequest request) {
        long start = System.currentTimeMillis();
        int maxResults = request.maxResults() != null ? request.maxResults() : DEFAULT_MAX_RESULTS;

        var filter = new FilterExpressionBuilder()
                .eq(METADATA_TOOL_ID, toolId)
                .build();

        // 即使 collection 中存了多个 Agent 的工具，也只检索当前 toolId 下的工具。
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(request.query())
                .topK(maxResults)
                .similarityThreshold(DEFAULT_SIMILARITY_THRESHOLD)
                .filterExpression(filter)
                .build());

        List<ToolReference> toolReferences = documents.stream()
                .map(this::toToolReference)
                .toList();

        return ToolSearchResponse.builder()
                .toolReferences(toolReferences)
                .totalMatches(toolReferences.size())
                .searchMetadata(ToolSearchResponse.SearchMetadata.builder()
                        .searchType(getClass().getSimpleName())
                        .query(request.query())
                        .searchTimeMs(System.currentTimeMillis() - start)
                        .build())
                .build();
    }

    @Override
    public void close() {
    }

    private ToolReference toToolReference(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return ToolReference.builder()
                .toolName(Objects.toString(metadata.get(METADATA_TOOL_NAME), ""))
                .summary(Objects.toString(metadata.get(METADATA_TOOL_DESCRIPTION), ""))
                .relevanceScore(document.getScore() != null ? document.getScore() : 0.0)
                .build();
    }

    private void loadStoredStateIfNecessary() {
        if (loadedStoredState) {
            return;
        }

        // VectorStore 没有普通 metadata 查询接口，这里使用 Milvus 原生客户端读取已存储的 hash。
        QueryParam queryParam = QueryParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(collectionName)
                .withExpr(idFieldName + " != \"\"")
                .withOutFields(List.of(idFieldName, metadataFieldName))
                .withLimit(10000L)
                .build();

        R<QueryResults> result = milvusClient.query(queryParam);
        if (result.getException() != null) {
            throw new IllegalStateException("Failed to query existing tool index documents", result.getException());
        }

        QueryResultsWrapper wrapper = new QueryResultsWrapper(result.getData());
        for (QueryResultsWrapper.RowRecord row : wrapper.getRowRecords()) {
            String documentId = Objects.toString(row.get(idFieldName), "");
            Object metadata = row.get(metadataFieldName);

            if (!toolId.equals(metadataValue(metadata, METADATA_TOOL_ID))) {
                continue;
            }

            String descriptionHash = metadataValue(metadata, METADATA_DESCRIPTION_HASH);
            if (!documentId.isBlank() && descriptionHash != null && !descriptionHash.isBlank()) {
                storedDescriptionHashes.put(documentId, descriptionHash);
            }
        }

        loadedStoredState = true;
    }

    private String metadataValue(Object metadata, String key) {
        if (metadata instanceof JsonObject jsonObject) {
            JsonElement value = jsonObject.get(key);
            return value != null && !value.isJsonNull() ? value.getAsString() : null;
        }
        if (metadata instanceof Map<?, ?> map) {
            Object value = map.get(key);
            return value != null ? value.toString() : null;
        }
        return null;
    }

    private String documentId(String toolName) {
        // Milvus VectorStore 创建的主键是最大长度 36 的 VarChar，所以使用基于名称生成的 UUID。
        return UUID.nameUUIDFromBytes((toolId + ":" + toolName).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String descriptionHash(ToolReference toolReference) {
        // 工具名或描述变化都会改变 hash，从而触发向量重建。
        return sha256(toolReference.toolName() + "\0" + toolReference.summary());
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
