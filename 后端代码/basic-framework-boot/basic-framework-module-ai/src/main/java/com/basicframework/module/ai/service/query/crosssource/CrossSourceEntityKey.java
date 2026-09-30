package com.basicframework.module.ai.service.query.crosssource;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_REVISION_CONFLICT;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 版本化实体键（Y04）：跨源关联的唯一连接键。
 *
 * <p>为什么必须是 {@code (键值, 映射版本)} 而不是裸键值：同一个"客户编号 C-1001"在 Y02 的
 * 映射版本 1 与版本 2 下可能指向**不同的统一对象**。跨版本按裸键值关联会把两份不同的事实
 * 并成一份——这正是 AT-070 里"跨系统同名不同实体"在数字上的表现形式。
 *
 * <p>因此本键把版本钉进类型里：不同版本的键**类型相同但不相等**，合并时必然撞上
 * {@link AiCrossSourceExecutionErrors#entityKeyRevisionConflict()}，不存在"忘了比版本"的路径。
 * 键值本身只允许字面量标识符字符，杜绝用拼接 payload 伪造实体键。
 */
public record CrossSourceEntityKey(String keyValue, Long mappingRevision) {

    /** 实体键值：字面量标识符字符（与 D05/D06 的标识符约束同一模式）。 */
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");

    public CrossSourceEntityKey {
        if (keyValue == null || !KEY_VALUE_PATTERN.matcher(keyValue).matches()) {
            throw exception(AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        }
        if (mappingRevision == null || mappingRevision < 1) {
            throw exception(AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        }
    }

    /** 判定一个键是否与本键同版本（版本不同即不可合并）。 */
    public boolean sameMappingRevision(CrossSourceEntityKey other) {
        return other != null && Objects.equals(mappingRevision, other.mappingRevision);
    }

    /** 键的稳定摘要（进日志与执行记录；只含键值与版本，不含任何数据）。 */
    public String describe() {
        return String.format(Locale.ROOT, "%s@r%s", keyValue, mappingRevision);
    }

    /**
     * 按已对齐的映射版本合并两个来源的键。
     *
     * <p>版本不一致直接阻断：把 {@code C-1001@r1} 与 {@code C-1001@r2} 当成同一个客户，
     * 会把两个版本下不同的事实合成一条——这类错误在报表上完全看不出来。
     */
    public static CrossSourceEntityKey join(CrossSourceEntityKey left, CrossSourceEntityKey right) {
        if (left == null || right == null) {
            throw exception(AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        }
        if (!left.sameMappingRevision(right)) {
            throw exception(AI_CROSS_SOURCE_ENTITY_KEY_REVISION_CONFLICT);
        }
        if (!left.keyValue().equals(right.keyValue())) {
            throw exception(AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        }
        return left;
    }

    /**
     * 校验一批键是否同版本（跨源合并的前置条件）。
     *
     * <p>空集合不视为通过：没有任何键就没有可关联的事实，让它"通过"会把空结果误报成完整结果。
     */
    public static Long requireSingleMappingRevision(List<CrossSourceEntityKey> keys) {
        if (keys == null || keys.isEmpty()) {
            throw exception(AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        }
        Long revision = keys.get(0).mappingRevision();
        for (CrossSourceEntityKey key : keys) {
            if (!Objects.equals(revision, key.mappingRevision())) {
                throw exception(AI_CROSS_SOURCE_ENTITY_KEY_REVISION_CONFLICT);
            }
        }
        return revision;
    }
}
