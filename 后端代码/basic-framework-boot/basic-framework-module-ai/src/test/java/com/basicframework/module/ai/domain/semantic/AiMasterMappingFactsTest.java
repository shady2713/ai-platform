package com.basicframework.module.ai.domain.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Y02 主数据映射事实算法：半开有效期、冲突检测（一对多/多对一）与版本指纹。
 *
 * <p>这些断言是 AT-070 的算法底座：有效期边界、同名不参与、冲突不取第一个。
 */
class AiMasterMappingFactsTest {

    private static final LocalDateTime JAN = LocalDateTime.of(2026, 1, 1, 0, 0);

    private static final LocalDateTime JUN = LocalDateTime.of(2026, 6, 1, 0, 0);

    private static final LocalDateTime DEC = LocalDateTime.of(2026, 12, 1, 0, 0);

    @Test
    void validityWindowIsHalfOpen() {
        // 起点属于本段，终点不属于本段：相邻两段不会重叠出双份事实
        assertThat(AiMasterMappingFacts.inForce(JAN, JUN, JAN)).isTrue();
        assertThat(AiMasterMappingFacts.inForce(JAN, JUN, JUN.minusNanos(1))).isTrue();
        assertThat(AiMasterMappingFacts.inForce(JAN, JUN, JUN)).isFalse();
        assertThat(AiMasterMappingFacts.inForce(JAN, null, DEC)).isTrue();
        assertThat(AiMasterMappingFacts.inForce(JAN, null, JAN.minusDays(1))).isFalse();
        // 缺失判定时刻或起点一律"不生效"（fail closed，不把 null 当"永久"）
        assertThat(AiMasterMappingFacts.inForce(JAN, JUN, null)).isFalse();
        assertThat(AiMasterMappingFacts.inForce(null, JUN, JAN)).isFalse();
    }

    @Test
    void inForceLinesKeepsOnlyCoveringRowsInOrder() {
        List<AiMasterMappingLine> lines = List.of(
                line(1L, "customer", "C-1", JAN, JUN),
                line(1L, "customer", "C-2", JUN, null),
                line(2L, "order", "O-1", JAN, JUN));

        assertThat(AiMasterMappingFacts.inForceLines(lines, JAN.plusDays(1)))
                .extracting(AiMasterMappingLine::sourceKey)
                .containsExactly("C-1", "O-1");
        assertThat(AiMasterMappingFacts.inForceLines(lines, JUN.plusDays(1)))
                .extracting(AiMasterMappingLine::sourceKey)
                .containsExactly("C-2");
        assertThat(AiMasterMappingFacts.inForceLines(List.of(), JAN)).isEmpty();
        assertThat(AiMasterMappingFacts.inForceLines(null, JAN)).isEmpty();
    }

    @Test
    void overlapsIsSymmetricAndOpenEndedWindowsOverlap() {
        AiMasterMappingLine early = line(1L, "customer", "C-1", JAN, JUN);
        AiMasterMappingLine late = line(1L, "customer", "C-2", JUN, null);
        AiMasterMappingLine open = line(1L, "customer", "C-3", JAN, null);

        assertThat(AiMasterMappingFacts.overlaps(early, late)).isFalse();
        assertThat(AiMasterMappingFacts.overlaps(late, early)).isFalse();
        assertThat(AiMasterMappingFacts.overlaps(early, open)).isTrue();
        assertThat(AiMasterMappingFacts.overlaps(open, open)).isTrue();
    }

    @Test
    void fingerprintIgnoresDisplayNameAndOrderButDetectsFactChanges() {
        AiMasterMappingLine first = line(1L, "customer", "C-1", JAN, null);
        AiMasterMappingLine second = line(1L, "customer", "C-2", JUN, null);
        String baseline = AiMasterMappingFacts.fingerprint(List.of(first, second));

        // 顺序无关：同一份事实的稳定摘要
        assertThat(AiMasterMappingFacts.fingerprint(List.of(second, first))).isEqualTo(baseline);
        // 展示名不参与：改展示名不该让历史版本"指纹不符"
        assertThat(AiMasterMappingFacts.fingerprint(List.of(
                        new AiMasterMappingLine(1L, 7L, "customer", "C-1", "改名后的展示名", "MANUAL", JAN, null), second)))
                .isEqualTo(baseline);
        // 源键/有效期/匹配方式是判定事实：任一变化都必须改变指纹
        assertThat(AiMasterMappingFacts.fingerprint(
                        List.of(new AiMasterMappingLine(1L, 7L, "customer", "C-9", "", "MANUAL", JAN, null), second)))
                .isNotEqualTo(baseline);
        assertThat(AiMasterMappingFacts.fingerprint(List.of(
                        new AiMasterMappingLine(1L, 7L, "customer", "C-1", "", "TRUSTED_FEED", JAN, null), second)))
                .isNotEqualTo(baseline);
        assertThat(AiMasterMappingFacts.fingerprint(List.of(second))).isNotEqualTo(baseline);
        assertThat(AiMasterMappingFacts.fingerprint(List.of())).isEqualTo(AiMasterMappingFacts.fingerprint(null));
    }

    @Test
    void instantConflictsDetectOneToManyAndManyToOneSeparately() {
        // 同一对象同一系统同一实体类型下两条同时生效：一对多
        List<AiMasterMappingLine> oneToMany =
                List.of(line(1L, "customer", "C-1", JAN, null), line(1L, "customer", "C-2", JAN, null));
        Map<String, List<AiMasterMappingLine>> lineConflicts =
                AiMasterMappingFacts.instantLineConflicts(oneToMany, JUN);
        assertThat(lineConflicts).containsOnlyKeys("1/7/customer");
        assertThat(AiMasterMappingFacts.instantObjectConflicts(oneToMany, JUN)).isEmpty();
        assertThat(AiMasterMappingFacts.describeConflicts(lineConflicts)).isEqualTo("1/7/customer");

        // 同一源键在同一时刻属于两个对象：多对一
        List<AiMasterMappingLine> manyToOne =
                List.of(line(1L, "customer", "C-1", JAN, null), line(2L, "customer", "C-1", JAN, null));
        assertThat(AiMasterMappingFacts.instantLineConflicts(manyToOne, JUN)).isEmpty();
        assertThat(AiMasterMappingFacts.instantObjectConflicts(manyToOne, JUN)).containsOnlyKeys("7/customer/C-1");

        // 时间窗不重叠时两者都不算冲突（换键是合法操作）
        List<AiMasterMappingLine> sequential =
                List.of(line(1L, "customer", "C-1", JAN, JUN), line(1L, "customer", "C-2", JUN, null));
        assertThat(AiMasterMappingFacts.instantLineConflicts(sequential, JAN.plusDays(1)))
                .isEmpty();
        assertThat(AiMasterMappingFacts.windowLineConflicts(sequential)).isEmpty();
        assertThat(AiMasterMappingFacts.windowObjectConflicts(sequential)).isEmpty();
    }

    @Test
    void windowConflictsCatchOverlapsOutsideTheCurrentInstant() {
        // 两条都在"未来"且互相重叠：当前时刻都不生效，但发布前必须拦住
        LocalDateTime future = LocalDateTime.of(2027, 1, 1, 0, 0);
        List<AiMasterMappingLine> overlapping = List.of(
                line(1L, "customer", "C-1", future, null), line(1L, "customer", "C-2", future.plusDays(1), null));

        assertThat(AiMasterMappingFacts.instantLineConflicts(overlapping, JAN)).isEmpty();
        assertThat(AiMasterMappingFacts.windowLineConflicts(overlapping)).containsOnlyKeys("1/7/customer");

        // 跨对象的多对一同样按时间窗判定
        List<AiMasterMappingLine> crossObject = List.of(
                line(1L, "customer", "C-1", future, null), line(2L, "customer", "C-1", future.plusDays(2), null));
        assertThat(AiMasterMappingFacts.windowObjectConflicts(crossObject)).containsOnlyKeys("7/customer/C-1");
        // 同一个对象在两个系统里各有同名源键：系统不同即不是冲突（不按字面相同合并）
        assertThat(AiMasterMappingFacts.windowObjectConflicts(List.of(
                        new AiMasterMappingLine(1L, 7L, "customer", "C-1", "", "MANUAL", JAN, null),
                        new AiMasterMappingLine(1L, 8L, "customer", "C-1", "", "MANUAL", JAN, null))))
                .isEmpty();
        // 同一个对象在同一系统里两条重叠：即使源键字面不同也是一对多冲突
        assertThat(AiMasterMappingFacts.windowObjectConflicts(List.of(
                        new AiMasterMappingLine(1L, 7L, "customer", "C-1", "", "MANUAL", JAN, null),
                        new AiMasterMappingLine(1L, 7L, "customer", "C-2", "", "MANUAL", JAN, null))))
                .isEmpty();
    }

    @Test
    void digestIsStableAndDistinguishesInput() {
        assertThat(AiMasterMappingFacts.digest("abc")).isEqualTo(AiMasterMappingFacts.digest("abc"));
        assertThat(AiMasterMappingFacts.digest("abc")).isNotEqualTo(AiMasterMappingFacts.digest("abd"));
        assertThat(AiMasterMappingFacts.digest(null)).isEqualTo(AiMasterMappingFacts.digest(""));
    }

    @Test
    void lineFactoryRejectsUnknownMatchMethodAndInvalidWindows() {
        assertThat(AiMasterMappingLine.of(1L, 7L, "customer", "C-1", "展示名", "manual", JAN, null)
                        .matchMethod())
                .isEqualTo("MANUAL");
        assertThatThrownBy(() -> AiMasterMappingLine.of(1L, 7L, "customer", "C-1", "", "SIMILARITY", JAN, null))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MASTER_MAPPING_ENTRY_INVALID.getCode());
        assertThatThrownBy(() -> AiMasterMappingLine.of(1L, 7L, "customer", "C-1", "", "MANUAL", JAN, JAN))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void lineIdentityKeysAndCanonicalTextExcludeDisplayName() {
        AiMasterMappingLine line = line(1L, "customer", "C-1", JAN, JUN);

        assertThat(line.sourceIdentity()).isEqualTo("7/customer/C-1");
        assertThat(line.objectSystemIdentity()).isEqualTo("1/7/customer");
        assertThat(line.matchMethodEnum()).isEqualTo(AiMasterMappingMatchMethod.MANUAL);
        assertThat(line.canonicalText()).doesNotContain("展示名");
    }

    private static AiMasterMappingLine line(
            Long masterObjectId, String entityType, String sourceKey, LocalDateTime from, LocalDateTime to) {
        return new AiMasterMappingLine(masterObjectId, 7L, entityType, sourceKey, "展示名", "MANUAL", from, to);
    }
}
