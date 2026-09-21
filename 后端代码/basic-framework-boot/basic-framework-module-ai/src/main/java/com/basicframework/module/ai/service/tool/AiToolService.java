package com.basicframework.module.ai.service.tool;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.service.tool.dto.AiToolSaveDTO;
import com.basicframework.module.ai.service.tool.dto.AiToolVersionSaveDTO;

/**
 * 工具注册与版本（D08）：工具身份、版本（政策 + 输入输出 schema）、发布与引用保护。
 *
 * <p>服务层只返回 DO / 服务 DTO，控制器负责转 VO。
 */
public interface AiToolService {

    /** 创建工具（绑定连接器；标识创建后不可修改）。 */
    Long create(AiToolSaveDTO saveDTO);

    /** 修改工具（只允许名称与说明）。 */
    void update(AiToolSaveDTO saveDTO);

    /** 启停工具（停用后不可执行，与"不存在"对外不可区分）。 */
    void updateStatus(Long id, Integer version, Boolean enabled);

    /** 删除工具（被引用时拒绝）。 */
    void delete(Long id, Integer version);

    /** 查询工具。 */
    AiToolDO getTool(Long id);

    /** 分页查询工具。 */
    PageResult<AiToolDO> getToolPage(PageParam pageParam, Long connectorId, String status);

    /** 创建版本草稿（政策默认 DENY；输入/输出 schema 与来源绑定落库）。 */
    Long createVersion(AiToolVersionSaveDTO saveDTO);

    /** 发布版本（首期只允许读工具；来源 operation 必须已发布）。 */
    void publishVersion(Long versionId, Integer version);

    /** 查询版本。 */
    AiToolVersionDO getVersion(Long versionId);

    /** 分页查询版本。 */
    PageResult<AiToolVersionDO> getVersionPage(Long toolId, PageParam pageParam);

    /** 按工具标识取**已发布**版本（执行判定用；未发布抛稳定错误码）。 */
    AiToolVersionDO requirePublishedVersion(String toolCode);
}
