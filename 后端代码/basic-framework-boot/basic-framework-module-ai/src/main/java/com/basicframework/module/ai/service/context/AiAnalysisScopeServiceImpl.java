package com.basicframework.module.ai.service.context;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_ANALYSIS_SCOPE_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_ANALYSIS_SCOPE_VERSION_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.module.ai.service.application.AiCrossSystemFacts;
import com.basicframework.module.ai.service.application.AiSystemCatalogService;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogQueryDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemEntryDTO;
import com.basicframework.module.ai.service.context.dto.AiAnalysisScopeSelectDTO;
import com.basicframework.module.ai.service.context.dto.AiAnalysisScopeSelectionDTO;
import com.basicframework.module.ai.service.context.dto.AiSelectedSystemDTO;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 范围选择实现（Y01）。
 *
 * <p>拒绝优先于"尽量满足"：
 * <ol>
 *   <li>模式、目录指纹、目标系统清单缺一不可；</li>
 *   <li>目录指纹与当前事实不一致直接 409（调用方看到的是过期的授权事实）；</li>
 *   <li>当前系统必须在目录里（连自己的系统都无权的会话不能做跨系统分析）；</li>
 *   <li>CROSS_SYSTEM 的目标必须**全部**命中目录，且至少包含一个非当前系统：任何一个不在目录里
 *       就整体拒绝（不静默剔除、不静默降级为单系统）；</li>
 *   <li>{@link #verify} 重新计算选择指纹：事实变化即 409，并且**返回服务端重算的结果**，
 *       调用方携带的 systems/modelCatalog 不会被采信。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AiAnalysisScopeServiceImpl implements AiAnalysisScopeService {

    private final AiSystemCatalogService catalogService;

    @Override
    public AiAnalysisScopeSelectionDTO select(AiAnalysisScopeSelectDTO selectDTO) {
        if (selectDTO == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiAnalysisScopeMode mode =
                AiAnalysisScopeMode.parse(selectDTO.getMode()).orElseThrow(() -> exception(AI_REQUEST_INVALID));
        if (!StringUtils.hasText(selectDTO.getCatalogFingerprint())) {
            // 没有"看到过的目录"就没有显式选择：拒绝猜测
            throw exception(AI_REQUEST_INVALID);
        }
        AiSystemCatalogDTO catalog = catalogService.discover(new AiSystemCatalogQueryDTO()
                .setApplicationId(selectDTO.getApplicationId())
                .setSubjectType(selectDTO.getSubjectType())
                .setExternalUserId(selectDTO.getExternalUserId()));
        if (!selectDTO.getCatalogFingerprint().equals(catalog.getCatalogFingerprint())) {
            throw exception(AI_ANALYSIS_SCOPE_VERSION_CONFLICT);
        }
        AiSystemEntryDTO current = currentEntry(catalog);
        List<AiSystemEntryDTO> selected = mode == AiAnalysisScopeMode.CURRENT_SYSTEM
                ? currentOnly(selectDTO, current)
                : crossSystem(selectDTO, catalog, current);

        List<AiSelectedSystemDTO> systems = new ArrayList<>();
        for (AiSystemEntryDTO entry : selected) {
            systems.add(new AiSelectedSystemDTO()
                    .setApplicationId(entry.getApplicationId())
                    .setAppCode(entry.getAppCode())
                    .setSystemName(entry.getSystemName())
                    .setCurrentSystem(entry.isCurrentSystem())
                    .setFederationId(entry.getFederationId())
                    .setSystemFingerprint(entry.getSystemFingerprint()));
        }
        return new AiAnalysisScopeSelectionDTO()
                .setApplicationId(catalog.getApplicationId())
                .setSubjectType(catalog.getSubjectType())
                .setExternalUserId(catalog.getExternalUserId())
                .setMode(mode.name())
                .setTargetSystemCodes(
                        systems.stream().map(AiSelectedSystemDTO::getAppCode).toList())
                .setCatalogFingerprint(catalog.getCatalogFingerprint())
                .setSystems(systems)
                .setModelCatalog(AiCrossSystemFacts.render(selected))
                .setSelectionFingerprint(fingerprint(mode, systems, catalog.getCatalogFingerprint()));
    }

    @Override
    public AiAnalysisScopeSelectionDTO verify(AiAnalysisScopeSelectionDTO selection) {
        if (selection == null || !StringUtils.hasText(selection.getSelectionFingerprint())) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiAnalysisScopeMode mode =
                AiAnalysisScopeMode.parse(selection.getMode()).orElseThrow(() -> exception(AI_REQUEST_INVALID));
        // CURRENT_SYSTEM 的目标清单是**结果**（只含当前系统），请求形态不允许携带目标；
        // 重算时按模式归一化，避免把结果的形态当成请求形态。
        AiAnalysisScopeSelectionDTO recomputed = select(new AiAnalysisScopeSelectDTO()
                .setApplicationId(selection.getApplicationId())
                .setSubjectType(selection.getSubjectType())
                .setExternalUserId(selection.getExternalUserId())
                .setMode(mode.name())
                .setTargetSystemCodes(
                        mode == AiAnalysisScopeMode.CROSS_SYSTEM ? selection.getTargetSystemCodes() : null)
                .setCatalogFingerprint(selection.getCatalogFingerprint()));
        if (!recomputed.getSelectionFingerprint().equals(selection.getSelectionFingerprint())) {
            throw exception(AI_ANALYSIS_SCOPE_VERSION_CONFLICT);
        }
        return recomputed;
    }

    private static AiSystemEntryDTO currentEntry(AiSystemCatalogDTO catalog) {
        for (AiSystemEntryDTO entry : catalog.getEntries()) {
            if (entry.isCurrentSystem()) {
                return entry;
            }
        }
        // 当前系统不在目录里：主体在当前系统没有可用范围，不做跨系统分析
        throw exception(AI_ANALYSIS_SCOPE_DENIED);
    }

    private static List<AiSystemEntryDTO> currentOnly(AiAnalysisScopeSelectDTO selectDTO, AiSystemEntryDTO current) {
        if (selectDTO.getTargetSystemCodes() != null
                && !selectDTO.getTargetSystemCodes().isEmpty()) {
            // 单系统选择不接受目标清单：显式选择不允许"顺带"扩大范围
            throw exception(AI_REQUEST_INVALID);
        }
        return List.of(current);
    }

    private static List<AiSystemEntryDTO> crossSystem(
            AiAnalysisScopeSelectDTO selectDTO, AiSystemCatalogDTO catalog, AiSystemEntryDTO current) {
        Set<String> targets = normalizeTargets(selectDTO.getTargetSystemCodes());
        if (targets.isEmpty()) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (!targets.contains(current.getAppCode())) {
            // 跨系统分析必须包含当前系统（会话身份所在系统）
            throw exception(AI_ANALYSIS_SCOPE_DENIED);
        }
        if (targets.size() < 2) {
            // 只勾当前系统不是跨系统分析，属于入参问题而不是权限问题
            throw exception(AI_REQUEST_INVALID);
        }
        for (String target : targets) {
            if (catalog.getEntries().stream()
                    .noneMatch(entry -> entry.getAppCode().equals(target))) {
                // 目录里没有的系统一律拒绝：不静默剔除、不静默降级为单系统
                throw exception(AI_ANALYSIS_SCOPE_DENIED);
            }
        }
        List<AiSystemEntryDTO> selected = catalog.getEntries().stream()
                .filter(entry -> targets.contains(entry.getAppCode()))
                .toList();
        if (selected.size() != targets.size()) {
            throw exception(AI_ANALYSIS_SCOPE_DENIED);
        }
        return selected;
    }

    /** 目标系统清单归一化：去空、去重（保序），并限制长度与条数。 */
    private static Set<String> normalizeTargets(List<String> targetSystemCodes) {
        Set<String> normalized = new LinkedHashSet<>();
        if (targetSystemCodes == null) {
            return normalized;
        }
        for (String code : targetSystemCodes) {
            if (code == null || code.isBlank()) {
                throw exception(AI_REQUEST_INVALID);
            }
            String trimmed = code.trim();
            if (trimmed.length() > 64) {
                throw exception(AI_REQUEST_INVALID);
            }
            normalized.add(trimmed);
        }
        return normalized;
    }

    /** 选择指纹：模式 + 选定系统的访问指纹 + 目录指纹（三者任一变化都要重新选择）。 */
    private static String fingerprint(
            AiAnalysisScopeMode mode, List<AiSelectedSystemDTO> systems, String catalogFingerprint) {
        StringBuilder builder = new StringBuilder();
        builder.append("mode=").append(mode.name()).append(';');
        for (AiSelectedSystemDTO system : systems) {
            builder.append("system=")
                    .append(system.getAppCode())
                    .append('#')
                    .append(system.getSystemFingerprint())
                    .append(';');
        }
        builder.append("catalog=").append(catalogFingerprint).append(';');
        return AiCrossSystemFacts.sha256(builder.toString());
    }
}
