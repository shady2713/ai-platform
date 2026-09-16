package com.basicframework.module.infra.framework.file.core.client;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 文件客户端的抽象类，提供模板方法，减少子类的冗余代码
 *
 */
@Slf4j
public abstract class AbstractFileClient<Config extends FileClientConfig> implements FileClient {

    /**
     * 配置编号
     */
    private final Long id;
    /**
     * 文件配置
     */
    protected Config config;
    /**
     * 原始的文件配置
     *
     * 原因：{@link #config} 可能被子类所修改，无法用于判断配置是否变更
     */
    private Config originalConfig;

    public AbstractFileClient(Long id, Config config) {
        this.id = id;
        this.config = config;
        this.originalConfig = config;
    }

    /**
     * 初始化
     */
    public final synchronized void init() {
        doInit();
        log.debug("[init][配置摘要({}) 初始化完成]", summarizeConfig(config));
    }

    /**
     * 自定义初始化
     */
    protected abstract void doInit();

    public final synchronized void refresh(Config config) {
        // 判断是否更新
        if (config.equals(this.originalConfig)) {
            return;
        }
        log.info("[refresh][配置摘要({}) 发生变化，重新初始化]", summarizeConfig(config));
        Config previousConfig = this.config;
        this.config = config;
        try {
            // 先完成初始化再提交配置版本；初始化失败时继续保留上一份可用配置。
            this.init();
            this.originalConfig = config;
        } catch (RuntimeException | Error exception) {
            this.config = previousConfig;
            throw exception;
        }
    }

    @Override
    public Long getId() {
        return id;
    }

    /**
     * 格式化文件的 URL 访问地址
     * 使用场景：local、ftp、db，通过 FileController 的 getFile 来获取文件内容
     *
     * @param domain 自定义域名
     * @param path 文件路径
     * @return URL 访问地址
     */
    protected String formatFileUrl(String domain, String path) {
        return StrUtil.format("{}/admin-api/infra/file/{}/get/{}", domain, getId(), path);
    }

    private String summarizeConfig(Config config) {
        if (config == null) {
            return String.format("id(%s) type(null)", id);
        }
        return String.format("id(%s) type(%s)", id, config.getClass().getSimpleName());
    }
}
