package com.basicframework.module.ai.service.run;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptDTO;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptResultDTO;

/**
 * 持久运行与幂等受理（O02）。
 *
 * <p>约束：
 * <ul>
 *   <li><b>受理幂等</b>：同一主体 + 同一幂等键 + 同一请求摘要只产生一个运行；
 *       同键异摘要返回 409，且不会重新发起模型调用；</li>
 *   <li><b>原子受理</b>：幂等记录、运行与首任务在同一事务内建立，任何一步失败都不留半成品；</li>
 *   <li><b>版本固定</b>：受理时解析发布版本（会话已固定则沿用固定版本，否则按别名解析并写入会话），
 *       同时冻结端点配置版本与内容摘要；运行链路只用快照；</li>
 *   <li><b>归属由服务端身份决定</b>：读取一律按当前主体过滤，越权与不存在同语义；</li>
 *   <li><b>响应不含秘密</b>：受理结果只有运行引用、状态与版本标识。</li>
 * </ul>
 */
public interface AiRunService {

    /** 受理一次运行（幂等）。 */
    AiRunAcceptResultDTO accept(AiRunAcceptDTO acceptDTO);

    /** 读取运行（非本人或不存在的运行同语义拒绝）。 */
    AiRunDO getRun(Long id);

    /** 当前主体的运行分页（按编号倒序）。 */
    PageResult<AiRunDO> getPage(PageParam pageParam);
}
