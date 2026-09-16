package com.basicframework.framework.mybatis.core.dataobject;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@link BaseDO} 单元测试
 *
 */
class BaseDOTest {

    @Test
    void auditFields_roundTripThroughAccessors() {
        TestBaseDO baseDO = new TestBaseDO();
        LocalDateTime createTime = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        LocalDateTime updateTime = LocalDateTime.of(2026, 2, 3, 4, 5, 6);

        baseDO.setCreateTime(createTime);
        baseDO.setUpdateTime(updateTime);
        baseDO.setCreator("creator-1");
        baseDO.setUpdater("updater-1");

        assertThat(baseDO.getCreateTime()).isEqualTo(createTime);
        assertThat(baseDO.getUpdateTime()).isEqualTo(updateTime);
        assertThat(baseDO.getCreator()).isEqualTo("creator-1");
        assertThat(baseDO.getUpdater()).isEqualTo("updater-1");
    }

    @Test
    void clean_resetsAllAuditFields() {
        TestBaseDO baseDO = new TestBaseDO();
        baseDO.setCreateTime(LocalDateTime.now());
        baseDO.setUpdateTime(LocalDateTime.now());
        baseDO.setCreator("creator-1");
        baseDO.setUpdater("updater-1");

        baseDO.clean();

        assertThat(baseDO.getCreateTime()).isNull();
        assertThat(baseDO.getUpdateTime()).isNull();
        assertThat(baseDO.getCreator()).isNull();
        assertThat(baseDO.getUpdater()).isNull();
    }

    @Test
    void clean_isSafeOnFreshInstance() {
        TestBaseDO baseDO = new TestBaseDO();

        baseDO.clean();

        assertThat(baseDO.getCreator()).isNull();
        assertThat(baseDO.getCreateTime()).isNull();
        assertThat(baseDO.getUpdater()).isNull();
        assertThat(baseDO.getUpdateTime()).isNull();
    }

    /** 仅为实例化抽象基类的测试实现。 */
    static final class TestBaseDO extends BaseDO {}
}
