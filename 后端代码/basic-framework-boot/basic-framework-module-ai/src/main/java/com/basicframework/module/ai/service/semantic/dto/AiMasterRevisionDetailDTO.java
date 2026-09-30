package com.basicframework.module.ai.service.semantic.dto;

import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectMappingDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectRevisionDO;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 映射版本详情（Y02）：版本头 + 条目 + **冲突预览**。
 *
 * <p>{@link #publishable} 用与发布完全相同的规则算出（草稿 + 非空 + 无时间窗冲突），
 * 因此管理页面能在发布前把"哪些源键冲突"如实展示出来，而不是等发布被拒才知道；
 * 冲突条目仍然列出（{@link #conflictKeys} 给出冲突键），要求操作员明确处理。
 *
 * <p>{@link #entryProblems} 是**编辑期预览**：发布时间窗冲突按"任意重叠"判定，过期按读取时的
 * 服务器时间判定。真正的判定（{@code resolve}）一律携带调用方显式给出的判定时刻，两者不共用时钟。
 */
@Data
@Accessors(chain = true)
public class AiMasterRevisionDetailDTO {

    /** 版本头 */
    private AiMasterObjectRevisionDO revision;

    /** 该版本的映射条目（按系统/实体类型/源键排序） */
    private List<AiMasterObjectMappingDO> entries;

    /** 条目问题（条目编号 → NONE/EXPIRED/CONFLICT） */
    private Map<Long, String> entryProblems;

    /** 冲突键（对象/系统/实体类型 或 系统/实体类型/源键；无冲突时为空） */
    private List<String> conflictKeys;

    /** 当前是否可直接发布（草稿 + 有条目 + 无冲突） */
    private boolean publishable;
}
