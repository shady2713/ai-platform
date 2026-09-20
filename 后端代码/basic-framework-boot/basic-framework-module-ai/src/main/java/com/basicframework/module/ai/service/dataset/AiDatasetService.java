package com.basicframework.module.ai.service.dataset;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionVerifyResultDTO;

/**
 * 语义数据集与版本（D04）：定义、版本、验证与发布。
 *
 * <p>服务层只返回 DO / 服务 DTO，控制器负责转 VO（协议层不反向依赖）。
 */
public interface AiDatasetService {

    /** 创建数据集（来源对象必须在该连接器授权白名单内）。 */
    Long create(AiDatasetSaveDTO saveDTO);

    /** 修改数据集（只允许名称与说明；标识与来源不可改）。 */
    void update(AiDatasetSaveDTO saveDTO);

    /** 启停数据集。 */
    void updateStatus(Long id, Integer version, Boolean enabled);

    /** 删除数据集（被报表引用时拒绝；版本行保留以维持可追溯）。 */
    void delete(Long id, Integer version);

    /** 查询数据集（不存在抛 404）。 */
    AiDatasetDO getDataset(Long id);

    /** 分页查询数据集。 */
    PageResult<AiDatasetDO> getDatasetPage(PageParam pageParam, Long connectorId, String status);

    /** 创建语义版本草稿（定义校验 + schemaHash）。 */
    Long createVersion(AiDatasetVersionSaveDTO saveDTO);

    /** 验证版本：与上游结构比对，得出 VERIFIED 或 DRIFTED（待验证）。 */
    AiDatasetVersionVerifyResultDTO verifyVersion(Long versionId, Integer version);

    /** 发布版本：要求已验证且上游结构未再变化。 */
    AiDatasetVersionVerifyResultDTO publishVersion(Long versionId, Integer version);

    /** 查询版本（不存在抛 404）。 */
    AiDatasetVersionDO getVersion(Long versionId);

    /** 分页查询版本。 */
    PageResult<AiDatasetVersionDO> getVersionPage(Long datasetId, PageParam pageParam);
}
