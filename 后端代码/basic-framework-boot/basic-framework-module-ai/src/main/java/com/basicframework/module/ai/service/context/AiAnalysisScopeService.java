package com.basicframework.module.ai.service.context;

import com.basicframework.module.ai.service.context.dto.AiAnalysisScopeSelectDTO;
import com.basicframework.module.ai.service.context.dto.AiAnalysisScopeSelectionDTO;

/**
 * 范围选择（Y01 的 "范围选择闭环"）：把"这次分析用哪些系统"从客户端意图变成服务端事实。
 *
 * <p>闭环由两个方法组成：
 * <ol>
 *   <li>{@link #select} 在**当前授权事实**上展开选择：当前系统必须在可访问目录里，
 *       目标系统必须逐个命中目录（不静默缩小、不静默新增），且调用方看到的目录指纹必须与
 *       当前事实一致；</li>
 *   <li>{@link #verify} 在之后任何时刻用同一份选择重新计算并比对指纹：事实变了就返回 409，
 *       而不是"按现在的权限凑一份尽量接近的系统集合"。</li>
 * </ol>
 *
 * <p>本服务是纯读：不写库、不缓存、不读时钟参与指纹，因此同一份事实在任何实例上得到同一结论。
 */
public interface AiAnalysisScopeService {

    /** 生成显式范围选择（失败即拒绝，绝不返回"尽力而为"的选择）。 */
    AiAnalysisScopeSelectionDTO select(AiAnalysisScopeSelectDTO selectDTO);

    /**
     * 重新核验一次历史选择：返回同一份选择（可用它的 systems/modelCatalog），
     * 依据事实已变化时抛 409 语义错误。
     */
    AiAnalysisScopeSelectionDTO verify(AiAnalysisScopeSelectionDTO selection);
}
