package com.basicframework.module.ai.adapter.connector.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** D03 池注册表：版本门、按池键复用、LRU 有界、按连接器关闭、空闲回收与状态上报。 */
class AiMysqlPoolRegistryTest {

    private final AtomicLong now = new AtomicLong(1_000L);

    private final List<HikariDataSource> created = new ArrayList<>();

    /** 用替身数据源替代真实建池：本类验证的是注册表纪律，真实建池由集成测试覆盖。 */
    private final AiMysqlPoolRegistry registry = new AiMysqlPoolRegistry(now::get) {

        @Override
        HikariDataSource create(AiMysqlConnectionTarget target) {
            HikariDataSource dataSource = mock(HikariDataSource.class);
            when(dataSource.isClosed()).thenReturn(false);
            created.add(dataSource);
            return dataSource;
        }
    };

    private static AiMysqlConnectionTarget target(long connectorId, int revision) {
        return new AiMysqlConnectionTarget(
                connectorId,
                "jdbc:mysql://db.internal:3306/crm?useSSL=true",
                "crm",
                "readonly",
                "secret-value",
                List.of("crm.orders"),
                revision);
    }

    @Test
    void acceptsOnlyMysql8() {
        assertThat(AiMysqlPoolRegistry.isSupportedVersion("8.4.11")).isTrue();
        assertThat(AiMysqlPoolRegistry.isSupportedVersion(" 8.0.36-log")).isTrue();
        assertThat(AiMysqlPoolRegistry.isSupportedVersion("5.7.44")).isFalse();
        assertThat(AiMysqlPoolRegistry.isSupportedVersion("10.11.2-MariaDB")).isFalse();
        assertThat(AiMysqlPoolRegistry.isSupportedVersion(null)).isFalse();
    }

    @Test
    void reusesPoolByKeyAndRebuildsOnCredentialRotation() {
        HikariDataSource first = registry.pool(target(9L, 1));
        assertThat(registry.pool(target(9L, 1))).isSameAs(first);
        assertThat(registry.poolCount()).isEqualTo(1);

        // 轮换秘密（凭据版本变化）必须换池：旧池关闭，避免继续用旧凭据的连接
        HikariDataSource rotated = registry.pool(target(9L, 2));
        assertThat(rotated).isNotSameAs(first);
        assertThat(registry.poolCount()).as("同一连接器只保留最新池").isEqualTo(1);
        verify(first).close();
    }

    @Test
    void keepsPoolCountBoundedByEvictingLeastRecentlyUsed() {
        for (long connectorId = 1; connectorId <= AiMysqlPoolRegistry.MAX_POOLS; connectorId++) {
            registry.pool(target(connectorId, 1));
            now.addAndGet(10L);
        }
        assertThat(registry.poolCount()).isEqualTo(AiMysqlPoolRegistry.MAX_POOLS);

        HikariDataSource oldest = created.get(0);
        registry.pool(target(AiMysqlPoolRegistry.MAX_POOLS + 1L, 1));

        assertThat(registry.poolCount()).isEqualTo(AiMysqlPoolRegistry.MAX_POOLS);
        verify(oldest).close();
        assertThat(registry.state(1L).state()).isEqualTo(AiMysqlPoolStateDTO.STATE_ABSENT);
    }

    @Test
    void closesPoolPerConnectorAndReportsState() {
        HikariDataSource dataSource = registry.pool(target(9L, 1));
        HikariPoolMXBean bean = mock(HikariPoolMXBean.class);
        when(dataSource.getHikariPoolMXBean()).thenReturn(bean);
        when(bean.getActiveConnections()).thenReturn(0);
        when(bean.getIdleConnections()).thenReturn(1);
        when(bean.getTotalConnections()).thenReturn(1);

        AiMysqlPoolStateDTO open = registry.state(9L);
        assertThat(open.state()).isEqualTo(AiMysqlPoolStateDTO.STATE_OPEN);
        assertThat(open.idleConnections()).isEqualTo(1);
        assertThat(open.activeConnections()).isZero();
        assertThat(registry.state(404L).state()).isEqualTo(AiMysqlPoolStateDTO.STATE_ABSENT);

        assertThat(registry.closePool(9L)).isTrue();
        verify(dataSource).close();
        assertThat(registry.state(9L).state()).isEqualTo(AiMysqlPoolStateDTO.STATE_ABSENT);
        assertThat(registry.closePool(9L)).as("重复关闭返回 false").isFalse();
    }

    @Test
    void reportsClosedStateWhenTheUnderlyingDataSourceIsAlreadyClosed() {
        HikariDataSource dataSource = registry.pool(target(9L, 1));
        when(dataSource.isClosed()).thenReturn(true);

        AiMysqlPoolStateDTO state = registry.state(9L);
        assertThat(state.state()).isEqualTo(AiMysqlPoolStateDTO.STATE_CLOSED);
        assertThat(state.activeConnections()).isZero();
        assertThat(state.idleConnections()).isZero();
        assertThat(state.totalConnections()).isZero();
        assertThat(AiMysqlPoolStateDTO.closed(9L).connectorId()).isEqualTo(9L);
    }

    @Test
    void closesIdlePoolsAndEverythingOnShutdown() {
        HikariDataSource first = registry.pool(target(1L, 1));
        HikariDataSource second = registry.pool(target(2L, 1));
        now.addAndGet(11 * 60_000L);
        HikariDataSource third = registry.pool(target(3L, 1));

        assertThat(registry.closeIdlePools()).as("只有长时间未用的池被回收").isEqualTo(2);
        verify(first).close();
        verify(second).close();
        assertThat(registry.poolCount()).isEqualTo(1);

        registry.closeAll();
        verify(third).close();
        assertThat(registry.poolCount()).isZero();
    }

    @Test
    void reportsUnavailableWhenUpstreamCannotBeReached() {
        // 真实建池路径（不替换 create）：连不上的上游必须落成稳定错误码，而不是把驱动异常抛给调用方
        AiMysqlPoolRegistry real = new AiMysqlPoolRegistry();
        AiMysqlConnectionTarget unreachable = new AiMysqlConnectionTarget(
                77L,
                "jdbc:mysql://127.0.0.1:1/crm?sslMode=DISABLED&connectTimeout=1000&socketTimeout=1000",
                "crm",
                "readonly",
                "secret-value",
                List.of("crm.orders"),
                1);

        assertThatThrownBy(() -> real.pool(unreachable)).satisfies(throwable -> {
            assertThat(throwable).isInstanceOf(ServiceException.class);
            assertThat(((ServiceException) throwable).getCode())
                    .isEqualTo(AiErrorCodeConstants.AI_CONNECTOR_MYSQL_UNAVAILABLE.getCode());
        });
        assertThat(real.poolCount()).as("建池失败不留半成品池").isZero();
    }

    @Test
    void describesFailureCauseChainWithoutExceptionText() {
        Throwable failure = new IllegalStateException(
                "连接串 jdbc:mysql://user:pw@host/db 不应出现在日志里",
                new java.sql.SQLException("Public Key Retrieval is not allowed"));

        assertThat(AiMysqlPoolRegistry.causeChain(failure))
                .as("只暴露原因链类型，不暴露异常正文")
                .isEqualTo("SQLException");
        assertThat(AiMysqlPoolRegistry.causeChain(new IllegalStateException("no cause")))
                .isEqualTo("-");
    }
}
