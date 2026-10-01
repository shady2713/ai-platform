package com.basicframework.module.ai.service.authorization.crosssource;

import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 用当前真实授权（A03）解析跨源事实（Y07）。
 *
 * <p>三条授权事实在这里落地，与 Y05 {@link CrossSourceAccessFacts} 的三级语义一一对应：
 * <ol>
 *   <li><b>来源系统</b>：当前 A03 资源模型里，来源系统的可访问性由该来源的数据集授权表达
 *       （与 Y05 验收 IT 同口径）。因此 {@code systemAuthorized} 与 {@code datasetAuthorized}
 *       读同一条 DATASET 授权——这不是把两级压成一级，而是当前模型下二者本就由同一份
 *       授权承载；映射仍是<b>独立</b>的第三次判定，压缩到两级会让"只读数据不做关联"绕过它。</li>
 *   <li><b>数据集</b>：{@code DATASET + datasetCode + READ}。</li>
 *   <li><b>实体映射</b>：{@code DATASET + "mapping:" + datasetCode + READ}。
 *       "能不能看到这条跨系统关联"在 A03 词表里同样是 DATASET 资源，不自造第二套资源类型
 *       （与 Y05 验收 IT 一致）。</li>
 * </ol>
 *
 * <p>每次调用都读当前授权版本，<b>不做跨请求缓存</b>：撤销必须立即生效。
 * "先撤销、后放行"在这个类里没有出现的路径——它每次都问 A03 当前怎么答。
 */
@Component
@RequiredArgsConstructor
public class A03CrossSourceAccessFactsResolver implements CrossSourceAccessFactsResolver {

    /** 映射授权的资源键前缀（"能不能看到这条跨系统关联"本身是一份需授权的事实）。 */
    private static final String MAPPING_KEY_PREFIX = "mapping:";

    private final AiAuthorizationService authorizationService;

    @Override
    public Map<String, CrossSourceAccessFacts> resolve(CrossSourceFactsQuery query) {
        Map<String, CrossSourceAccessFacts> facts = new LinkedHashMap<>();
        for (SourceBinding binding : query.bindings()) {
            boolean datasetAllowed = isGranted(query, binding.datasetCode());
            CrossSourceAccessFacts resolved = new CrossSourceAccessFacts(
                    binding.role(),
                    binding.datasetCode(),
                    binding.datasetCode(),
                    datasetAllowed,
                    datasetAllowed,
                    isGranted(query, MAPPING_KEY_PREFIX + binding.datasetCode()));
            // 键必须用 facts.key()（角色为空时退化为 "系统/数据集"），
            // 而不是直接用 binding.role()：judge 是按同一个 key() 取事实的，
            // 两侧键口径不一致会让它误判成"事实缺失"（入参不合法）而不是"无权"。
            facts.put(resolved.key(), resolved);
        }
        return facts;
    }

    /** 读当前授权：查不到即视为无权（fail-closed），绝不因为"没查到"就默认放行。 */
    private boolean isGranted(CrossSourceFactsQuery query, String resourceKey) {
        if (resourceKey == null || resourceKey.isBlank()) {
            return false;
        }
        return authorizationService
                .authorize(
                        query.applicationId(),
                        query.subjectType(),
                        query.externalUserId(),
                        AiResourceType.DATASET,
                        resourceKey,
                        AiAction.READ,
                        List.of(resourceKey))
                .isAllowed();
    }
}
