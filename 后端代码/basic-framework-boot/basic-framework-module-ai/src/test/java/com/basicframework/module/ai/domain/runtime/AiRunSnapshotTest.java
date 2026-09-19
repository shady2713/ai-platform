package com.basicframework.module.ai.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import java.util.List;
import org.junit.jupiter.api.Test;

/** S03 运行版本固定：固定值只覆盖"版本"，不覆盖权限，且能逐条比对库中当前值。 */
class AiRunSnapshotTest {

    private static final Long SERVICE_ID = 9L;

    private static final Long RELEASE_ID = 21L;

    private static final String CONTENT_HASH = "a".repeat(64);

    private static AiServiceReleaseDO release() {
        return new AiServiceReleaseDO()
                .setId(RELEASE_ID)
                .setServiceId(SERVICE_ID)
                .setReleaseVersion(2)
                .setModelEndpointId(3L)
                .setEndpointConfigRevision(7)
                .setRequiredCapabilities("TEXT")
                .setEvalThreshold(80)
                .setContentHash(CONTENT_HASH)
                .setStatus(AiServiceReleaseDO.STATUS_ACTIVE)
                .setVersion(1);
    }

    private static AiServiceResourceDO binding(Long id, String key, int version) {
        return new AiServiceResourceDO()
                .setId(id)
                .setServiceId(SERVICE_ID)
                .setReleaseId(RELEASE_ID)
                .setResourceType("REPORT")
                .setResourceKey(key)
                .setActions("READ")
                .setStatus(AiServiceResourceDO.STATUS_ACTIVE)
                .setVersion(version);
    }

    @Test
    void capturesReleaseModelRevisionAndResourceVersions() {
        AiRunSnapshot pin = AiRunSnapshot.of(release(), List.of(binding(11L, "report-1", 0), binding(12L, "kb-1", 3)));

        assertThat(pin.getServiceId()).isEqualTo(SERVICE_ID);
        assertThat(pin.getReleaseId()).isEqualTo(RELEASE_ID);
        assertThat(pin.getReleaseVersion()).isEqualTo(2);
        assertThat(pin.getContentHash()).isEqualTo(CONTENT_HASH);
        assertThat(pin.getModelEndpointId()).isEqualTo(3L);
        assertThat(pin.getModelRevision()).isEqualTo(7);
        assertThat(pin.resourceBindingIds()).containsExactly(11L, 12L);
        assertThat(pin.getResources())
                .extracting(AiRunSnapshot.ResourcePin::getResourceKey, AiRunSnapshot.ResourcePin::getVersion)
                .containsExactly(tuple("report-1", 0), tuple("kb-1", 3));
    }

    @Test
    void contentMatchRequiresServiceReleaseAndHash() {
        AiRunSnapshot pin = AiRunSnapshot.of(release(), List.of());

        assertThat(pin.pinsContentOf(release())).isTrue();
        assertThat(pin.pinsContentOf(release().setContentHash("b".repeat(64))))
                .as("内容被改写即视为不是同一固定版本")
                .isFalse();
        assertThat(pin.pinsContentOf(release().setId(22L)))
                .as("换了版本编号即视为不是同一固定版本")
                .isFalse();
        assertThat(pin.pinsContentOf(release().setServiceId(77L))).as("跨服务不算命中").isFalse();
        assertThat(pin.pinsContentOf(null)).isFalse();
    }

    @Test
    void modelRevisionAndResourcesAreComparedAgainstCurrentValues() {
        AiRunSnapshot pin = AiRunSnapshot.of(release(), List.of(binding(11L, "report-1", 0)));

        assertThat(pin.sameModelRevision(7)).isTrue();
        assertThat(pin.sameModelRevision(8)).as("端点配置漂移不算同一固定版本").isFalse();
        assertThat(pin.sameModelRevision(null)).isFalse();

        assertThat(pin.sameResources(List.of(binding(11L, "report-1", 0))))
                .as("逐条命中")
                .isTrue();
        assertThat(pin.sameResources(List.of(binding(11L, "report-1", 1))))
                .as("绑定版本变化（例如被解绑）不算同一固定版本")
                .isFalse();
        assertThat(pin.sameResources(List.of(binding(11L, "report-2", 0))))
                .as("同一编号但资源标识变化不算命中")
                .isFalse();
        assertThat(pin.sameResources(List.of(binding(11L, "report-1", 0), binding(12L, "kb-1", 0))))
                .as("绑定条数变化不算命中")
                .isFalse();
        assertThat(pin.sameResources(List.of())).isFalse();
        assertThat(pin.sameResources(null)).isFalse();

        // 顺序无关：两条绑定的返回顺序不影响判定
        AiRunSnapshot twoPins =
                AiRunSnapshot.of(release(), List.of(binding(11L, "report-1", 0), binding(12L, "kb-1", 3)));
        assertThat(twoPins.sameResources(List.of(binding(12L, "kb-1", 3), binding(11L, "report-1", 0))))
                .isTrue();
    }
}
