package com.basicframework.framework.common.util.cache;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Cache 工具类
 *
 */
@lombok.NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class CacheUtils {

    /**
     * 异步刷新的 LoadingCache 最大缓存数量
     *
     */
    private static final Integer CACHE_MAX_SIZE = 10000;

    /** 缓存刷新属于短时计算任务，复用 JVM 公共池，避免每个缓存创建无法关闭的线程池。 */
    private static final Executor CACHE_REFRESH_EXECUTOR = ForkJoinPool.commonPool();

    /**
     * 构建异步刷新的 LoadingCache 对象
     *
     * 或者简单理解：和“全局”、“系统”相关的缓存使用当前方法
     *
     * @param duration 过期时间
     * @param loader  CacheLoader 对象
     * @return LoadingCache 对象
     */
    public static <K, V> LoadingCache<K, V> buildAsyncReloadingCache(Duration duration, CacheLoader<K, V> loader) {
        return CacheBuilder.newBuilder()
                .maximumSize(CACHE_MAX_SIZE)
                // 只阻塞当前数据加载线程，其他线程返回旧值
                .refreshAfterWrite(duration)
                // 通过 asyncReloading 实现全异步加载，包括 refreshAfterWrite 被阻塞的加载线程
                .build(CacheLoader.asyncReloading(loader, CACHE_REFRESH_EXECUTOR));
    }
}
