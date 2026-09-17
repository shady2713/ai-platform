package com.basicframework.module.infra.api.file;

import com.basicframework.module.infra.api.file.dto.FileBusinessAccessContext;

/**
 * 业务文件授权 SPI：由拥有业务对象的模块实现（例如 AI 模块判断知识文档、会话附件与报表的当前访问权）。
 *
 * <p>infra 保留存储、魔数/路径/归档校验与读取执行权；是否允许某主体读取某业务对象的文件由本 SPI 决定。
 * 约束：
 * <ul>
 *   <li>每个业务类型只允许一个实现，重复注册在启动期失败；</li>
 *   <li>文件声明的业务类型没有对应实现时一律拒绝读取（fail-closed），不回退到管理权限；</li>
 *   <li>实现必须每次按当前状态判定，不得缓存跨请求的授权结果。</li>
 * </ul>
 */
public interface FileBusinessAccessProvider {

    /**
     * 本 Provider 负责的业务类型，例如 ai_knowledge_document。
     *
     * @return 业务类型标识，不能为空
     */
    String getBusinessType();

    /**
     * 判定主体是否可读该业务对象关联的文件。
     *
     * @param context 判定上下文
     * @return true 表示允许读取
     */
    boolean canRead(FileBusinessAccessContext context);

    /**
     * 判定主体是否可删除该业务对象关联的文件。
     *
     * <p>默认拒绝：业务文件删除必须由业务模块显式实现，管理权限与"看起来有权读"都不能作为删除依据。
     *
     * @param context 判定上下文
     * @return true 表示允许删除
     */
    default boolean canDelete(FileBusinessAccessContext context) {
        return false;
    }
}
