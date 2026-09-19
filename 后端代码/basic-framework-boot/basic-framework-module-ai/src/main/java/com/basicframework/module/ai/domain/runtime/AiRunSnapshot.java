package com.basicframework.module.ai.domain.runtime;

import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import lombok.Getter;

/**
 * 运行版本固定（S03）：一次运行只使用它开始时固定的发布版本、端点配置版本与资源版本。
 *
 * <p>固定的是**版本**，不是权限：快照里不保存授权结论，资源绑定状态与当前授权在每次解析时
 * 按最新值重新判定，因此发布别名切换（发布/回退/停用）只影响后续运行的解析结果，
 * 旧版本永远不会把已经失效的权限"带回来"。
 *
 * <p>会话沿用版本时用 {@link #pinsContentOf} 校验 releaseId 与内容摘要，用
 * {@link #sameModelRevision} 与 {@link #sameResources} 校验端点配置与绑定自固定以来未被改动。
 */
@Getter
public final class AiRunSnapshot {

    /** 服务编号 */
    private final Long serviceId;

    /** 固定的发布版本编号 */
    private final Long releaseId;

    /** 固定的发布版本号（同一服务内递增） */
    private final Integer releaseVersion;

    /** 固定的内容摘要（发布内容的稳定标识） */
    private final String contentHash;

    /** 固定的模型端点编号 */
    private final Long modelEndpointId;

    /** 固定的端点配置版本（模型修订号） */
    private final Integer modelRevision;

    /** 固定的资源绑定版本清单 */
    private final List<ResourcePin> resources;

    private AiRunSnapshot(
            Long serviceId,
            Long releaseId,
            Integer releaseVersion,
            String contentHash,
            Long modelEndpointId,
            Integer modelRevision,
            List<ResourcePin> resources) {
        this.serviceId = serviceId;
        this.releaseId = releaseId;
        this.releaseVersion = releaseVersion;
        this.contentHash = contentHash;
        this.modelEndpointId = modelEndpointId;
        this.modelRevision = modelRevision;
        this.resources = resources;
    }

    /** 由发布版本与其冻结的绑定快照生成运行固定值。 */
    public static AiRunSnapshot of(AiServiceReleaseDO release, List<AiServiceResourceDO> bindings) {
        List<ResourcePin> pins = new ArrayList<>();
        for (AiServiceResourceDO binding : bindings) {
            pins.add(new ResourcePin(
                    binding.getId(),
                    binding.getResourceType(),
                    binding.getResourceKey(),
                    binding.getVersion() == null ? 0 : binding.getVersion()));
        }
        return new AiRunSnapshot(
                release.getServiceId(),
                release.getId(),
                release.getReleaseVersion(),
                release.getContentHash(),
                release.getModelEndpointId(),
                release.getEndpointConfigRevision(),
                Collections.unmodifiableList(pins));
    }

    /**
     * 该发布版本是否仍是本运行固定的那一份内容。
     *
     * <p>服务、版本编号与内容摘要三者都命中才算同一个版本：只比编号无法发现内容被改写的库行。
     */
    public boolean pinsContentOf(AiServiceReleaseDO release) {
        return release != null
                && Objects.equals(serviceId, release.getServiceId())
                && Objects.equals(releaseId, release.getId())
                && Objects.equals(contentHash, release.getContentHash());
    }

    /** 端点配置版本是否仍是固定值（漂移即拒绝运行，不静默改用新配置）。 */
    public boolean sameModelRevision(Integer currentRevision) {
        return Objects.equals(modelRevision, currentRevision);
    }

    /**
     * 资源绑定快照是否与固定时逐条一致（按绑定编号、资源标识与版本；顺序无关）。
     *
     * <p>只要有一条绑定被解绑（版本推进）、被换掉资源标识或整行消失，就返回 false：
     * 固定运行宁可以稳定错误结束，也不改用另一条绑定。
     */
    public boolean sameResources(List<AiServiceResourceDO> bindings) {
        if (bindings == null || bindings.size() != resources.size()) {
            return false;
        }
        for (ResourcePin pin : resources) {
            AiServiceResourceDO matched = bindings.stream()
                    .filter(binding -> Objects.equals(pin.getId(), binding.getId()))
                    .findFirst()
                    .orElse(null);
            if (matched == null
                    || !Objects.equals(pin.getResourceType(), matched.getResourceType())
                    || !Objects.equals(pin.getResourceKey(), matched.getResourceKey())
                    || !Objects.equals(pin.getVersion(), matched.getVersion())) {
                return false;
            }
        }
        return true;
    }

    /** 固定的资源绑定编号（供诊断与测试比对，不含授权结论）。 */
    public List<Long> resourceBindingIds() {
        return resources.stream().map(ResourcePin::getId).toList();
    }

    /** 单条资源绑定固定值：只记录"用到哪一个版本的哪一条绑定"，不记录动作结果。 */
    @Getter
    public static final class ResourcePin {

        /** 绑定编号 */
        private final Long id;

        /** 资源类型 */
        private final String resourceType;

        /** 资源标识 */
        private final String resourceKey;

        /** 固定时的绑定版本 */
        private final Integer version;

        ResourcePin(Long id, String resourceType, String resourceKey, Integer version) {
            this.id = id;
            this.resourceType = resourceType;
            this.resourceKey = resourceKey;
            this.version = version;
        }
    }
}
