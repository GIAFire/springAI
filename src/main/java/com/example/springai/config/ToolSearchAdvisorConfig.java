package com.example.springai.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolSearchAdvisorConfig {

    @Bean
    @ConditionalOnMissingBean(ToolIndex.class)
    public ToolIndex toolIndex(MilvusServiceClient milvusClient,
                               EmbeddingModel embeddingModel,
                               BatchingStrategy batchingStrategy,
                               MilvusVectorStoreProperties milvusProperties,
                               @Value("${spring.ai.chat.client.tool-search-advisor.milvus.collection-name:tool_data}") String toolCollectionName,
                               @Value("${spring.ai.chat.client.tool-search-advisor.milvus.tool-id:default}") String toolId) {
        // 工具检索和业务知识库使用不同 collection，避免 RAG 检索到工具说明文本。
        // 这里不额外注册 VectorStore Bean，防止影响业务侧默认 VectorStore 自动装配。
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

        // 默认 VectorToolIndex 会用随机 UUID 入库；StableMilvusToolIndex 使用稳定 ID 和描述 hash，
        // 可避免每次请求或应用重启后重复写入相同工具向量。
        return new StableMilvusToolIndex(
                toolVectorStore,
                milvusClient,
                milvusProperties.getDatabaseName(),
                toolCollectionName,
                milvusProperties.getIdFieldName(),
                milvusProperties.getMetadataFieldName(),
                toolId);
    }

    @Bean
    public ToolCallingAdvisor.Builder<?> toolCallingAdvisorBuilder(
            ToolCallingManager toolCallingManager,
            ToolIndex toolIndex,
            @Value("${spring.ai.chat.client.tool-search-advisor.max-results:2}") Integer maxResults) {
        return ToolSearchToolCallingAdvisor.builder()
                .systemMessageSuffix("\n你可以使用一个名为 toolSearchTool 的特殊工具来按需发现可用工具。\n" +
                        "当你需要执行某个操作，但当前上下文中没有合适工具时，应先调用 toolSearchTool 搜索相关工具。\n" +
                        "搜索结果会返回可用工具名称，系统随后会把这些工具的完整定义提供给你调用。")
                .toolCallingManager(toolCallingManager)
                .toolIndex(toolIndex)
                // 限制每轮工具检索返回数量，减少无关工具 schema 注入到后续请求。
                .maxResults(maxResults)
                .advisorOrder(ToolCallingAdvisor.DEFAULT_ORDER);
    }
}
