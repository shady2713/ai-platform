package com.basicframework.module.ai.service.theme;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.theme.AiThemeDO;
import com.basicframework.module.ai.service.theme.dto.AiThemeEffectiveDTO;
import com.basicframework.module.ai.service.theme.dto.AiThemeSaveDTO;

/**
 * 主题修订（C04）：版本存储、发布/回退与有效主题解析。
 *
 * <p>服务层只返回 DO 与服务 DTO，控制器负责转 VO（协议层不反向依赖）。
 * 发布与回退是同一动作：把目标修订置为 {@code PUBLISHED}，把原生效修订置为 {@code SUPERSEDED}；
 * 同一应用同时最多一个 {@code PUBLISHED}，由同一事务内的行锁保证。
 */
public interface AiThemeService {

    /** 新建主题修订草稿（tokens/布局校验并规范化；修订号 = 应用内最大修订号 + 1）。 */
    Long create(AiThemeSaveDTO saveDTO);

    /** 查询主题修订（不存在抛 404）。 */
    AiThemeDO getTheme(Long id);

    /** 分页查询主题修订（按编号倒序，可按应用与发布状态过滤）。 */
    PageResult<AiThemeDO> getThemePage(PageParam pageParam, Long applicationId, String publicationState);

    /** 发布或回退：目标修订必须属于该应用且不是当前生效修订。 */
    void publish(Long id, Integer version);

    /** 解析有效主题：平台默认 → 应用已发布修订（无发布修订时给出平台默认与来源标记）。 */
    AiThemeEffectiveDTO resolveEffective(Long applicationId);
}
