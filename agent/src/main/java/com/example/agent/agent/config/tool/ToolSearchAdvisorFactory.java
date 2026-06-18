package com.example.agent.agent.config.tool;

import io.milvus.client.MilvusServiceClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.util.Assert;

/**
 * 按 Agent 创建工具检索 Advisor。
 *
 * <p>每个 Agent 都应该使用独立的 toolSetId。工具向量可以放在同一个 Milvus collection 中，
 * 但 StableMilvusToolIndex 会把 toolSetId 写入 metadata，并在检索时按 toolSetId 过滤。</p>
 */
public class ToolSearchAdvisorFactory {

    private final VectorStore toolVectorStore;

    private final MilvusServiceClient milvusClient;

    private final String databaseName;

    private final String collectionName;

    private final String idFieldName;

    private final String metadataFieldName;

    private final ToolCallingManager toolCallingManager;

    private final Integer maxResults;

    public ToolSearchAdvisorFactory(VectorStore toolVectorStore, MilvusServiceClient milvusClient, String databaseName,
                                    String collectionName, String idFieldName, String metadataFieldName,
                                    ToolCallingManager toolCallingManager, Integer maxResults) {
        this.toolVectorStore = toolVectorStore;
        this.milvusClient = milvusClient;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
        this.idFieldName = idFieldName;
        this.metadataFieldName = metadataFieldName;
        this.toolCallingManager = toolCallingManager;
        this.maxResults = maxResults;
    }

    public ToolSearchToolCallingAdvisor create(String toolSetId) {
        Assert.hasText(toolSetId, "toolSetId must not be empty");

        // 默认 VectorToolIndex 会用随机 UUID 入库；StableMilvusToolIndex 使用稳定 ID 和描述 hash，
        // 可避免每次请求或应用重启后重复写入相同工具向量。
        ToolIndex toolIndex = new StableMilvusToolIndex(
                toolVectorStore,
                milvusClient,
                databaseName,
                collectionName,
                idFieldName,
                metadataFieldName,
                toolSetId);

        return ToolSearchToolCallingAdvisor.builder()
                .systemMessageSuffix("\n你可以使用一个名为 toolSearchTool 的特殊工具来按需发现当前智能体可用工具。\n" +
                        "当你需要执行某个操作，但当前上下文中没有合适工具时，应先调用 toolSearchTool 搜索相关工具。\n" +
                        "搜索结果只会从当前智能体被授权的工具集中返回，随后系统会把这些工具的完整定义提供给你调用。")
                .toolCallingManager(toolCallingManager)
                .toolIndex(toolIndex)
                // 限制每轮工具检索返回数量，减少无关工具 schema 注入到后续请求。
                .maxResults(maxResults)
                .advisorOrder(ToolCallingAdvisor.DEFAULT_ORDER)
                .build();
    }
}
