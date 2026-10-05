package com.basicframework.module.ai.adapter.knowledge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集合维度的有界并发缓存。
 *
 * <p>独立成类而不是留在 {@link QdrantRestKnowledgeIndexAdapter} 里有两个原因：
 *
 * <ul>
 *   <li><b>可测</b>：适配器走真实 HTTP，"超限即清空"这条不变量在适配器层面
 *       很难构造（要真的发 1024 次请求），而它恰恰是本类存在的理由；</li>
 *   <li><b>并发语义要能被读懂</b>：本类用的是并发容器，适配器其余部分是纯 HTTP 编排，
 *       混在一起会让人误以为整个适配器都是并发的。</li>
 * </ul>
 *
 * <p>容量语义：<b>满了就整体清空</b>，而不是维护 LRU。维度在集合创建后不可变，
 * 清空后调用方会重新探测，因此"粗暴清空"的唯一代价是多一次探测调用；
 * 换来的是不需要额外的数据结构与淘汰推理。
 */
final class BoundedDimensionCache {

    /** 默认容量。集合名含世代号，长驻进程每次换代都会新增键，因此必须有上界。 */
    static final int DEFAULT_CAPACITY = 1024;

    private final Map<String, Integer> entries;

    private final int capacity;

    BoundedDimensionCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("缓存容量必须为正整数");
        }
        this.capacity = capacity;
        this.entries = new ConcurrentHashMap<>();
    }

    Integer get(String collection) {
        return entries.get(collection);
    }

    void put(String collection, int dimension) {
        if (entries.size() >= capacity) {
            entries.clear();
        }
        entries.put(collection, dimension);
    }

    /** 当前条目数：供测试与观测使用。 */
    int size() {
        return entries.size();
    }
}
