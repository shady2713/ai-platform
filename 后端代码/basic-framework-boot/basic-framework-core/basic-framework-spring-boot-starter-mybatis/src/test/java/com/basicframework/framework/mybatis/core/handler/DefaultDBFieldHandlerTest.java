package com.basicframework.framework.mybatis.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.security.CurrentUserProvider;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import java.time.LocalDateTime;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/** 便于实例化的 BaseDO 子类（仅用于填充测试） */
class TestBaseDO extends BaseDO {}

/**
 * 通用参数填充处理器单元测试
 *
 * 验证 5D SPI 改造后的行为：CurrentUserProvider 注入（ObjectProvider）
 * 且缺席时回退"不填充用户"；时间与用户只在空值时填充，不覆盖显式赋值；
 * 非 BaseDO 与 null 安全跳过。
 */
@ExtendWith(MockitoExtension.class)
class DefaultDBFieldHandlerTest {

    @Mock
    private ObjectProvider<CurrentUserProvider> provider;

    @Mock
    private CurrentUserProvider currentUserProvider;

    private DefaultDBFieldHandler handler() {
        return new DefaultDBFieldHandler(provider);
    }

    private MetaObject metaOf(BaseDO baseDO) {
        return SystemMetaObject.forObject(baseDO);
    }

    @Test
    void insertFill_fillsTimeAndUserWhenAllEmpty() {
        when(provider.getIfAvailable()).thenReturn(currentUserProvider);
        when(currentUserProvider.getLoginUserId()).thenReturn(1L);
        BaseDO baseDO = new TestBaseDO();
        LocalDateTime before = LocalDateTime.now();

        handler().insertFill(metaOf(baseDO));

        assertThat(baseDO.getCreateTime()).isBetween(before, LocalDateTime.now());
        assertThat(baseDO.getUpdateTime()).isNotNull();
        assertThat(baseDO.getCreator()).isEqualTo("1");
        assertThat(baseDO.getUpdater()).isEqualTo("1");
    }

    @Test
    void insertFill_keepsExplicitValues() {
        when(provider.getIfAvailable()).thenReturn(currentUserProvider);
        when(currentUserProvider.getLoginUserId()).thenReturn(1L);
        BaseDO baseDO = new TestBaseDO();
        LocalDateTime fixed = LocalDateTime.of(2026, 1, 1, 0, 0);
        baseDO.setCreateTime(fixed);
        baseDO.setUpdateTime(fixed);
        baseDO.setCreator("explicit");
        baseDO.setUpdater("explicit");

        handler().insertFill(metaOf(baseDO));

        assertThat(baseDO.getCreateTime()).isEqualTo(fixed);
        assertThat(baseDO.getUpdateTime()).isEqualTo(fixed);
        assertThat(baseDO.getCreator()).isEqualTo("explicit");
        assertThat(baseDO.getUpdater()).isEqualTo("explicit");
    }

    @Test
    void insertFill_skipsUserWhenProviderAbsentButFillsTime() {
        // security starter 缺席：getIfAvailable() 返回 null → 用户字段不填充
        when(provider.getIfAvailable()).thenReturn(null);
        BaseDO baseDO = new TestBaseDO();

        handler().insertFill(metaOf(baseDO));

        assertThat(baseDO.getCreateTime()).isNotNull();
        assertThat(baseDO.getUpdateTime()).isNotNull();
        assertThat(baseDO.getCreator()).isNull();
        assertThat(baseDO.getUpdater()).isNull();
    }

    @Test
    void insertFill_skipsNonBaseDo() {
        Object plain = new Object();

        handler().insertFill(SystemMetaObject.forObject(plain));
        // 不做任何事即通过；无字段可断言，仅为覆盖分支
    }

    @Test
    void insertFill_handlesNullMetaObject() {
        handler().insertFill(null);
        // null 安全：不抛异常
    }

    @Test
    void updateFill_fillsUpdateTimeAndUpdater() {
        when(provider.getIfAvailable()).thenReturn(currentUserProvider);
        when(currentUserProvider.getLoginUserId()).thenReturn(2L);
        BaseDO baseDO = new TestBaseDO();

        handler().updateFill(metaOf(baseDO));

        assertThat(baseDO.getUpdateTime()).isNotNull();
        assertThat(baseDO.getUpdater()).isEqualTo("2");
        // creator 不属于 update 阶段填充
        assertThat(baseDO.getCreator()).isNull();
    }

    @Test
    void updateFill_keepsExplicitUpdaterAndTime() {
        when(provider.getIfAvailable()).thenReturn(currentUserProvider);
        when(currentUserProvider.getLoginUserId()).thenReturn(2L);
        BaseDO baseDO = new TestBaseDO();
        LocalDateTime fixed = LocalDateTime.of(2026, 1, 1, 0, 0);
        baseDO.setUpdateTime(fixed);
        baseDO.setUpdater("explicit");

        handler().updateFill(metaOf(baseDO));

        assertThat(baseDO.getUpdateTime()).isEqualTo(fixed);
        assertThat(baseDO.getUpdater()).isEqualTo("explicit");
    }

    @Test
    void updateFill_skipsUpdaterWhenProviderAbsent() {
        when(provider.getIfAvailable()).thenReturn(null);
        BaseDO baseDO = new TestBaseDO();

        handler().updateFill(metaOf(baseDO));

        assertThat(baseDO.getUpdateTime()).isNotNull();
        assertThat(baseDO.getUpdater()).isNull();
    }
}
