package com.basicframework.module.ai.adapter.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 有界维度缓存的回归测试。
 *
 * <p>为什么这段逻辑必须有独立测试：它是 {@link QdrantRestKnowledgeIndexAdapter}
 * 之所以从无界 {@code LinkedHashMap} 改过来的两条理由——并发安全与容量上界。
 * 适配器走真实 HTTP，要在它层面验证"超限即清空"得发 1024 次请求；
 * 抽成独立类后两条不变量都能被直接钉住。
 */
class BoundedDimensionCacheTest {

    @Test
    void getReturnsNullForUnknownKey() {
        assertThat(new BoundedDimensionCache(4).get("kb_a_g1")).isNull();
    }

    @Test
    void putThenGetReturnsTheStoredDimension() {
        BoundedDimensionCache cache = new BoundedDimensionCache(4);
        cache.put("kb_a_g1", 128);
        assertThat(cache.get("kb_a_g1")).isEqualTo(128);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void overwriteKeepsSingleEntryAndLatestValue() {
        BoundedDimensionCache cache = new BoundedDimensionCache(4);
        cache.put("kb_a_g1", 128);
        cache.put("kb_a_g1", 256);
        assertThat(cache.get("kb_a_g1")).isEqualTo(256);
        assertThat(cache.size()).as("同一集合名不应占两个槽位").isEqualTo(1);
    }

    /**
     * 集合名含世代号，长驻进程每次换代都是新键——上界就是为这条路径存在的。
     */
    @Test
    void clearsWhenCapacityIsReachedSoGrowthIsBounded() {
        BoundedDimensionCache cache = new BoundedDimensionCache(3);
        cache.put("g1", 1);
        cache.put("g2", 1);
        cache.put("g3", 1);
        assertThat(cache.size()).as("恰好装满时不应提前清空").isEqualTo(3);

        cache.put("g4", 1);

        assertThat(cache.size()).as("第 4 个键进来时应先清空再写入").isEqualTo(1);
        assertThat(cache.get("g4")).isEqualTo(1);
        assertThat(cache.get("g1")).as("清空后旧键应消失，调用方会重新探测").isNull();
    }

    @Test
    void sizeNeverExceedsCapacity() {
        BoundedDimensionCache cache = new BoundedDimensionCache(5);
        for (int i = 0; i < 500; i++) {
            cache.put("kb_" + i + "_g" + i, 128);
            assertThat(cache.size()).isLessThanOrEqualTo(5);
        }
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new BoundedDimensionCache(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedDimensionCache(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 并发读写不得损坏结构。
     *
     * <p>这是本次修复的起因：原先的 {@code LinkedHashMap} 无任何同步，
     * 入库作业线程写、检索请求线程读，并发 {@code put} 叠加 {@code get}
     * 可能让内部结构损坏。这个测试不能"证明"没有 bug（覆盖不全），
     * 但能在换成并发容器后把回归挡住。
     */
    @Test
    void survivesConcurrentReadWrite() throws InterruptedException {
        BoundedDimensionCache cache = new BoundedDimensionCache(64);
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                int base = t * perThread;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            cache.put("kb_" + (base + i), 128);
                            // 读一半命中、读另一半已被清空都可能，两种都必须不抛异常
                            cache.get("kb_" + (base + i));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).as("并发写入应在超时前完成").isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(cache.size()).isBetween(1, 64);
    }
}
