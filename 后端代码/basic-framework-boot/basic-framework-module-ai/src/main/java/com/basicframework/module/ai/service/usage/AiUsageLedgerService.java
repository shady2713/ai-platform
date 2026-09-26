package com.basicframework.module.ai.service.usage;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.usage.AiUsageLedgerDO;
import com.basicframework.module.ai.service.usage.dto.AiUsageRecordDTO;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用量账本（Q02）：按调用去重记录、按应用/服务/来源聚合。
 *
 * <p>服务层只返回 DO 与聚合结果（Map），控制器负责转 VO。
 */
public interface AiUsageLedgerService {

    /** 记录一次调用：同一 invocationId 重复写入不重复累计（返回是否为首次写入）。 */
    boolean record(AiUsageRecordDTO record);

    /** 查询某次运行的账本明细（按调用顺序）。 */
    java.util.List<AiUsageLedgerDO> listByRun(Long runId);

    /** 分页查询账本（应用/服务/时间窗）。 */
    PageResult<AiUsageLedgerDO> page(
            PageParam pageParam, Long applicationId, Long serviceId, LocalDateTime from, LocalDateTime to);

    /** 按计量来源聚合（条数 + token 合计；UNKNOWN 行的 token 为 0 计入合计但不代表真实用量）。 */
    Map<String, Object> summaryBySource(Long applicationId, LocalDateTime from, LocalDateTime to);

    /** 按服务聚合。 */
    java.util.List<Map<String, Object>> summaryByService(Long applicationId, LocalDateTime from, LocalDateTime to);
}
