package com.basicframework.module.ai.service.evaluation;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalCaseDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalSuiteDO;
import com.basicframework.module.ai.service.evaluation.dto.AiEvalCaseSaveDTO;
import com.basicframework.module.ai.service.evaluation.dto.AiEvalSuiteSaveDTO;
import java.util.List;

/**
 * 评测套件与样例（Q04）：配置面。
 *
 * <p>状态机：{@code DRAFT}（可编辑）→ 冻结 {@code FROZEN}（内容摘要固定）→
 * 需要再改时创建**新修订**回到 {@code DRAFT}。冻结后编辑一律拒绝，
 * 因此"套件修改不改变已有 eval 运行"不是靠自觉：运行行自带套件摘要与逐例快照。
 */
public interface AiEvalSuiteService {

    /** 创建套件（同一应用下标识唯一；样例只允许 L1/L2 分级）。 */
    Long createSuite(AiEvalSuiteSaveDTO saveDTO);

    /** 更新套件（仅草稿；标识不可修改）。 */
    void updateSuite(AiEvalSuiteSaveDTO saveDTO);

    /** 冻结套件：校验期望规则、记录样例数与内容摘要，修订号 +1。 */
    void freezeSuite(Long suiteId, Integer version);

    /** 冻结后创建新修订（回到草稿，修订号 +1；历史运行的摘要不受影响）。 */
    void newRevision(Long suiteId, Integer version);

    /** 读取套件（不存在即拒绝）。 */
    AiEvalSuiteDO requireSuite(Long suiteId);

    /** 套件分页（可按应用与状态过滤）。 */
    PageResult<AiEvalSuiteDO> pageSuites(PageParam pageParam, Long applicationId, String status);

    /** 新增样例（仅草稿；期望规则必须合规）。 */
    Long createCase(AiEvalCaseSaveDTO saveDTO);

    /** 更新样例（仅草稿）。 */
    void updateCase(AiEvalCaseSaveDTO saveDTO);

    /** 删除样例（仅草稿；软删除，历史运行保留快照摘要）。 */
    void deleteCase(Long caseId, Integer version);

    /** 读取样例（不存在即拒绝）。 */
    AiEvalCaseDO requireCase(Long caseId);

    /** 套件内样例（按标识升序：执行与报告顺序稳定）。 */
    List<AiEvalCaseDO> listCases(Long suiteId);
}
