package com.basicframework.module.ai.adapter.knowledge;

import java.util.List;
import java.util.Map;

/**
 * 向量索引最小端口（K01）：候选向量服务必须满足的最小能力集合。
 *
 * <p>设计约束（来自 K01 的验收）：
 * <ul>
 *   <li><b>维度固定</b>：集合维度在创建时确定，写入维度不一致必须**拒绝**（不允许服务端静默截断或补零）；</li>
 *   <li><b>服务端过滤</b>：ACL 过滤在服务端执行，调用方提供的过滤值必须经过 {@link KnowledgeFilter} 净化，
 *       特殊字符不能绕过过滤条件；</li>
 *   <li><b>删除可验证</b>：删除后按同一条件检索必须查不到（删除语义可被独立验证）；</li>
 *   <li><b>认证与传输</b>：支持 API Key 认证；生产部署必须走 TLS（本端口只接受 https 基址，
 *       本地验证可用显式开关放开 http）。</li>
 * </ul>
 *
 * <p>本端口是**候选验证用**的最小集合：通过 Go/No-Go 后再由 K 系列的正式知识库实现扩展
 * （分块、索引版本、重建与配额等）。
 */
public interface KnowledgeIndexPort {

    /** 集合信息（维度与点数）。 */
    record CollectionInfo(String name, int dimension, long pointsCount) {}

    /** 索引文档：标识 + 向量 + 载荷（载荷只放可过滤字段与引用，不放正文）。 */
    record IndexDocument(String id, float[] vector, Map<String, Object> payload) {}

    /** 检索命中：标识 + 相似度 + 载荷。 */
    record SearchHit(String id, double score, Map<String, Object> payload) {}

    /** 确保集合存在且维度一致；维度不一致时抛 {@link KnowledgeIndexException}。 */
    CollectionInfo ensureCollection(String collection, int dimension);

    /** 批量写入（upsert 语义：同 id 覆盖）。 */
    void upsert(String collection, List<IndexDocument> documents);

    /** 按向量检索（topK），可带服务端过滤（ACL）。 */
    List<SearchHit> search(String collection, float[] vector, int topK, KnowledgeFilter filter);

    /**
     * 按过滤条件删除（删除后按同一条件检索必须为空）。
     *
     * <p>返回**删除前按同一条件统计的条数**：向量服务的删除接口不返回条数，
     * 计数在删除前用 count 接口取得，用于审计与验证"删了多少"。
     */
    long delete(String collection, KnowledgeFilter filter);

    /** 删除全部（测试与重建用；正式实现按索引版本重建）。 */
    void deleteAll(String collection);

    /** 集合当前信息。 */
    CollectionInfo describe(String collection);
}
