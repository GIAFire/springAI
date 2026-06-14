package com.example.agent.agent.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolSearchAdvisorConfig {

    @Bean
    public ToolSearchAdvisorFactory toolSearchAdvisorFactory(MilvusServiceClient milvusClient,
                                                             EmbeddingModel embeddingModel,
                                                             BatchingStrategy batchingStrategy,
                                                             MilvusVectorStoreProperties milvusProperties,
                                                             ToolCallingManager toolCallingManager,
                                                             @Value("${spring.ai.chat.client.tool-search-advisor.milvus.collection-name:tool_data}") String toolCollectionName,
                                                             @Value("${spring.ai.chat.client.tool-search-advisor.max-results:5}") Integer maxResults) {
        // 工具检索和业务知识库使用不同 collection，避免 RAG 检索到工具说明文本。
        // 这里不额外注册 VectorStore Bean，防止影响业务侧默认 VectorStore 自动装配。
        // 构造工具检索专用 Milvus 向量库，多个 Agent 可以共用同一个 collection。
        MilvusVectorStore toolVectorStore = MilvusVectorStore.builder(milvusClient, embeddingModel)
                .initializeSchema(milvusProperties.isInitializeSchema())
                .databaseName(milvusProperties.getDatabaseName())
                .collectionName(toolCollectionName)
                .embeddingDimension(milvusProperties.getEmbeddingDimension())
                .indexType(IndexType.valueOf(milvusProperties.getIndexType().name()))
                .metricType(MetricType.valueOf(milvusProperties.getMetricType().name()))
                .indexParameters(milvusProperties.getIndexParameters())
                .iDFieldName(milvusProperties.getIdFieldName())
                .autoId(false)
                .contentFieldName(milvusProperties.getContentFieldName())
                .metadataFieldName(milvusProperties.getMetadataFieldName())
                .embeddingFieldName(milvusProperties.getEmbeddingFieldName())
                .batchingStrategy(batchingStrategy)
                .build();
        try {
            toolVectorStore.afterPropertiesSet();
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to initialize tool search Milvus collection", e);
        }

        // 工厂按 Agent 创建 ToolSearchAdvisor，每个 Agent 传入自己的 toolSetId。
        // 这样同一个 Milvus collection 里可以存多套工具，但检索时只命中当前 Agent 的工具集。
        return new ToolSearchAdvisorFactory(
                toolVectorStore,
                milvusClient,
                milvusProperties.getDatabaseName(),
                toolCollectionName,
                milvusProperties.getIdFieldName(),
                milvusProperties.getMetadataFieldName(),
                toolCallingManager,
                maxResults);
    }
}
