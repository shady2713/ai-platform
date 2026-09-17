package com.basicframework.framework.ai.core.model;

/**
 * 受管模型客户端工厂（M02 冻结）：按端点快照创建客户端，并提供失效与关闭路径。
 *
 * <p>实现要求：客户端按 {@link ModelEndpointSnapshot#key()} 缓存（有界），
 * 密钥轮换或端点修改后 {@link #invalidate(Long)} 关闭对应客户端；{@link #close()} 关闭全部。
 * 动态配置不得通过修改全局单例实现。
 */
public interface ModelClientFactory extends AutoCloseable {

    /**
     * 按快照获取或创建客户端；同一键返回同一实例。
     */
    ModelPort getOrCreate(ModelEndpointSnapshot snapshot);

    /** 关闭并移除指定端点的全部客户端（轮换、停用、地址迁移时调用）。 */
    void invalidate(Long endpointId);

    /** 关闭全部客户端。 */
    @Override
    void close();
}
