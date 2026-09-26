package com.basicframework.module.ai.service.evaluation;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalCaseDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalSuiteDO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测内容摘要（Q04）：套件冻结与运行快照共用同一算法。
 *
 * <p>只把**判定相关**的字段纳入摘要（标识、服务、主体、分级、样例问题与期望规则、级别、是否复核），
 * 名称与说明不影响判定，因此改标题不会让历史运行的摘要对不上；改规则一定会。
 */
public final class AiEvalSuiteDigests {

    private AiEvalSuiteDigests() {}

    /** 套件 + 样例的内容摘要（冻结与运行快照都调用它）。 */
    public static String of(AiEvalSuiteDO suite, List<AiEvalCaseDO> cases) {
        List<AiEvalCaseDO> ordered = cases == null ? List.of() : cases;
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("applicationId", suite.getApplicationId());
        head.put("caseCount", ordered.size());
        head.put("code", suite.getCode());
        head.put("dataLevel", suite.getDataLevel());
        head.put("serviceId", suite.getServiceId());
        head.put("subjectType", suite.getSubjectType());
        head.put("externalUserId", suite.getExternalUserId());
        List<String> caseDigests = new ArrayList<>();
        for (AiEvalCaseDO item : ordered) {
            caseDigests.add(caseDigest(item));
        }
        head.put("cases", caseDigests);
        return AiEvalDigest.digest(AiEvalDigest.canonicalJson(head));
    }

    /** 单例摘要：期望规则规范化后参与计算，因此书写空白不影响摘要。 */
    public static String caseDigest(AiEvalCaseDO item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("caseKey", item.getCaseKey());
        map.put("checks", AiEvalDigest.canonicalJsonText(item.getChecksJson()));
        map.put("expectVersion", item.getExpectVersion());
        map.put("needsReview", item.getNeedsReview());
        map.put("question", item.getQuestion());
        map.put("severity", item.getSeverity());
        return AiEvalDigest.digest(AiEvalDigest.canonicalJson(map));
    }

    /** 运行行的逐例快照摘要（执行即冻结，报告可复现）。 */
    public static String summaryJson(List<AiEvalCaseDO> cases) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AiEvalCaseDO item : cases == null ? List.<AiEvalCaseDO>of() : cases) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("caseKey", item.getCaseKey());
            entry.put("digest", caseDigest(item));
            entry.put("needsReview", item.getNeedsReview());
            entry.put("severity", item.getSeverity());
            items.add(entry);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cases", items);
        return JsonUtils.toJsonString(summary);
    }
}
