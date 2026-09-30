package com.basicframework.module.ai.service.semantic;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectMappingDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectRevisionDO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingEntrySaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectSaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterRevisionDetailDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterRevisionDraftDTO;
import java.util.List;

/**
 * 跨系统主数据映射的**管理面**（Y02）：统一对象的生命周期、版本草稿与映射条目登记/发布。
 *
 * <p>四条不变量：
 * <ol>
 *   <li><b>对象标识不可修改</b>：登记后只能改名称/类型/说明/状态；历史报表按标识引用对象；</li>
 *   <li><b>版本不可变</b>：草稿（DRAFT）可增删条目，发布（PUBLISHED）后条目与指纹冻结，
 *       改映射只能新建版本——"换映射版本不改旧结果"由结构保证，而不是靠调用方自觉；</li>
 *   <li><b>发布是独立审核</b>：发布人必须不同于草稿创建人；发布前按时间窗检查一对多/多对一冲突，
 *       有冲突则拒绝发布（把冲突留在草稿里让操作员处理，而不是发布出去）；</li>
 *   <li><b>登记只认源键</b>：条目必须给出源键与匹配方式（人工/可信导入），未知匹配方式一律拒绝。</li>
 * </ol>
 */
public interface AiMasterObjectService {

    /** 单版本映射条目预算：登记与目录读取都据此有界，超出拒绝返回部分内容。 */
    int MAX_ENTRIES_PER_REVISION = 200;

    /** 新建统一对象（标识全局唯一）。 */
    Long createObject(AiMasterObjectSaveDTO saveDTO);

    /** 修改统一对象（名称/类型/说明；标识不可改，带乐观锁）。 */
    void updateObject(AiMasterObjectSaveDTO saveDTO);

    /** 启用/停用统一对象（停用后一切判定阻断）。 */
    void updateObjectStatus(Long id, Integer version, boolean enabled);

    /** 查询统一对象（不存在抛 404 语义）。 */
    AiMasterObjectDO getObject(Long id);

    /** 按标识查询统一对象（不存在抛 404 语义）。 */
    AiMasterObjectDO getObjectByCode(String objectCode);

    /** 统一对象分页（按类型/状态过滤，关键字匹配标识或名称）。 */
    PageResult<AiMasterObjectDO> getObjectPage(PageParam pageParam, String objectType, String status, String keyword);

    /** 新建草稿映射版本（一个对象同时只允许一个未发布草稿）。 */
    Long createRevision(AiMasterRevisionDraftDTO draftDTO);

    /** 在草稿版本里登记一条源键映射（重复登记拒绝；冲突允许暂存，发布时阻断）。 */
    Long addMappingEntry(AiMasterMappingEntrySaveDTO saveDTO);

    /** 删除草稿版本里的映射条目（已发布版本的条目不可删除）。 */
    void removeMappingEntry(Long entryId, Integer version);

    /** 发布草稿版本（独立审核 + 冲突检查 + 冻结指纹，并推进对象的当前版本）。 */
    AiMasterObjectRevisionDO publishRevision(Long masterObjectId, Long revisionNo, Integer version);

    /** 查询映射版本（不存在抛 404 语义）。 */
    AiMasterObjectRevisionDO getRevision(Long masterObjectId, Long revisionNo);

    /** 映射版本分页（可按对象与状态过滤）。 */
    PageResult<AiMasterObjectRevisionDO> getRevisionPage(PageParam pageParam, Long masterObjectId, String status);

    /** 某版本的映射条目（按系统/实体类型/源键排序）。 */
    List<AiMasterObjectMappingDO> listEntries(Long masterObjectId, Long revisionNo);

    /** 某版本的详情（版本头 + 条目 + 冲突预览 + 是否可发布）。 */
    AiMasterRevisionDetailDTO getRevisionDetail(Long masterObjectId, Long revisionNo);
}
