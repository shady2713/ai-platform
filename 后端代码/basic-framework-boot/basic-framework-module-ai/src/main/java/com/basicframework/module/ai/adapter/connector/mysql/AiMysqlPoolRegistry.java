package com.basicframework.module.ai.adapter.connector.mysql;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_MYSQL_UNAVAILABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_MYSQL_VERSION_UNSUPPORTED;

import com.basicframework.framework.common.exception.ServiceException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 独立只读连接池注册表（D03）：每个连接器一组自己的连接池，**不接入平台默认 MyBatis 路由**。
 *
 * <p>为什么独立成池：连接器的目标是**外部**库，账号、库、超时与生命周期都与平台自身数据源不同；
 * 混进动态数据源会让"外部只读连接"继承平台的事务、连接与失败语义（也把外部库暴露给平台 ORM）。
 *
 * <p>资源纪律（本卡的验收点）：
 * <ul>
 *   <li><b>池有界</b>：单池最大 {@value #MAX_POOL_SIZE} 条连接、{@code minimumIdle = 0}；
 *       注册表最多同时保留 {@value #MAX_POOLS} 组池，超出按 LRU 关闭最久未用的池；</li>
 *   <li><b>连接只读</b>：池级 {@code readOnly = true}（驱动层）＋ 连接初始化 SQL 设定服务端
 *       {@code max_execution_time} 兜底（服务端层），配合只读账号构成三层防线；</li>
 *   <li><b>可关闭、可观测</b>：停用/删除连接器或空闲超时后关闭池，{@link #state(Long)} 给出
 *       活动/空闲/总连接数，用于验证"连接已归还"；容器关闭时 {@link #closeAll()} 全部释放。</li>
 * </ul>
 *
 * <p>池键包含地址与凭据版本：改地址或轮换秘密会重建池（旧池关闭），而白名单等非连接配置变化不重建池。
 */
@Slf4j
@Component
public class AiMysqlPoolRegistry {

    /** 注册表最多同时保留的池数量（超出按 LRU 关闭）。 */
    public static final int MAX_POOLS = 16;

    /** 单池最大连接数：只读查询是短事务，4 条足够，且限制单连接器对上游的连接占用。 */
    public static final int MAX_POOL_SIZE = 4;

    /** 建池/取连接超时（毫秒）。 */
    private static final int CONNECTION_TIMEOUT_MILLIS = 3_000;

    /** 空闲连接回收（毫秒）：配合 minimumIdle = 0，空闲池最终不占用上游连接。 */
    private static final int IDLE_TIMEOUT_MILLIS = 60_000;

    /** 池整体空闲多久后回收（毫秒）。 */
    private static final long IDLE_POOL_CLOSE_MILLIS = 10 * 60_000L;

    /** 服务端语句执行上限（毫秒）：客户端语句超时的兜底，防止客户端断连后上游仍在跑。 */
    private static final int SERVER_MAX_EXECUTION_MILLIS = 30_000;

    /** 注册表（LRU：访问顺序 = 迭代顺序）。 */
    private final Map<String, Pooled> pools = new LinkedHashMap<>(16, 0.75f, true);

    /** 单调时钟（毫秒），便于测试控制空闲回收。 */
    private final java.util.function.LongSupplier clock;

    public AiMysqlPoolRegistry() {
        this(System::currentTimeMillis);
    }

    AiMysqlPoolRegistry(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    /** 取得（必要时创建）目标对应的只读池。 */
    public synchronized HikariDataSource pool(AiMysqlConnectionTarget target) {
        String key = target.poolKey();
        Pooled existing = pools.get(key);
        if (existing != null) {
            existing.touch(clock.getAsLong());
            return existing.dataSource();
        }
        evictLeastRecentlyUsedIfFull();
        // 同一连接器换池（改地址或轮换秘密）时先关旧池：旧凭据的连接不能继续留在池里
        closePool(target.connectorId());
        HikariDataSource dataSource = create(target);
        pools.put(key, new Pooled(target.connectorId(), dataSource, clock.getAsLong()));
        return dataSource;
    }

    /** 池资源状态（用于验证连接归还与关闭）。 */
    public synchronized AiMysqlPoolStateDTO state(Long connectorId) {
        for (Pooled pooled : pools.values()) {
            if (pooled.connectorId().equals(connectorId)) {
                HikariDataSource dataSource = pooled.dataSource();
                if (dataSource.isClosed()) {
                    return AiMysqlPoolStateDTO.closed(connectorId);
                }
                HikariPoolMXBean bean = dataSource.getHikariPoolMXBean();
                if (bean == null) {
                    return new AiMysqlPoolStateDTO(connectorId, AiMysqlPoolStateDTO.STATE_OPEN, 0, 0, 0);
                }
                return new AiMysqlPoolStateDTO(
                        connectorId,
                        AiMysqlPoolStateDTO.STATE_OPEN,
                        bean.getActiveConnections(),
                        bean.getIdleConnections(),
                        bean.getTotalConnections());
            }
        }
        return AiMysqlPoolStateDTO.absent(connectorId);
    }

    /** 关闭某连接器的全部池（停用/删除连接器时调用；返回是否关闭了池）。 */
    public synchronized boolean closePool(Long connectorId) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Pooled> entry : pools.entrySet()) {
            if (entry.getValue().connectorId().equals(connectorId)) {
                keys.add(entry.getKey());
            }
        }
        for (String key : keys) {
            close(pools.remove(key));
        }
        return !keys.isEmpty();
    }

    /** 回收长时间未使用的池（返回关闭数量）。 */
    public synchronized int closeIdlePools() {
        long deadline = clock.getAsLong() - IDLE_POOL_CLOSE_MILLIS;
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Pooled> entry : pools.entrySet()) {
            if (entry.getValue().lastUsed() < deadline) {
                keys.add(entry.getKey());
            }
        }
        for (String key : keys) {
            close(pools.remove(key));
        }
        return keys.size();
    }

    /** 当前池数量（测试与运维观测用）。 */
    public synchronized int poolCount() {
        return pools.size();
    }

    /** 容器关闭：释放全部池。 */
    @PreDestroy
    public synchronized void closeAll() {
        List<Pooled> closing = new ArrayList<>(pools.values());
        pools.clear();
        closing.forEach(this::close);
    }

    /** 受支持的版本判定：只对接 MySQL 8（MariaDB 与 5.x 一律拒绝）。 */
    public static boolean isSupportedVersion(String version) {
        return version != null && version.trim().startsWith("8.");
    }

    /** 建池（包级可见：单测用替身数据源验证注册表纪律，真实建池由集成测试覆盖）。 */
    HikariDataSource create(AiMysqlConnectionTarget target) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(target.jdbcUrl());
        config.setUsername(target.username());
        config.setPassword(target.password());
        config.setPoolName("ai-mysql-" + target.connectorId());
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        config.setIdleTimeout(IDLE_TIMEOUT_MILLIS);
        config.setReadOnly(true);
        config.setAutoCommit(true);
        // 服务端兜底：客户端断连后上游语句不会无限跑
        config.setConnectionInitSql("SET SESSION max_execution_time = " + SERVER_MAX_EXECUTION_MILLIS);
        HikariDataSource dataSource = null;
        try {
            dataSource = new HikariDataSource(config);
            requireSupportedServer(dataSource);
            return dataSource;
        } catch (RuntimeException failure) {
            if (dataSource != null) {
                dataSource.close();
            }
            if (failure instanceof ServiceException) {
                throw failure;
            }
            // 连接失败必须可诊断，但不能记录异常正文（正文可能回显连接串/凭据）：
            // 只记异常类型、原因链类型与 SQL 错误码/状态，足以区分"网络不通/账号被拒/传输被拒/版本不符"
            log.warn(
                    "只读连接池创建失败：connectorId={}, reason={}, causes={}, sqlCode={}, sqlState={}",
                    target.connectorId(),
                    failure.getClass().getSimpleName(),
                    causeChain(failure),
                    sqlErrorCode(failure),
                    sqlErrorState(failure));
            throw exception(AI_CONNECTOR_MYSQL_UNAVAILABLE);
        }
    }

    /** 原因链的类型序列（只取类名，不含异常正文）。 */
    static String causeChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable current = failure.getCause(); current != null; current = current.getCause()) {
            if (chain.length() > 0) {
                chain.append('>');
            }
            chain.append(current.getClass().getSimpleName());
            if (chain.length() > 200) {
                break;
            }
        }
        return chain.isEmpty() ? "-" : chain.toString();
    }

    private static String sqlErrorCode(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                return String.valueOf(sqlException.getErrorCode());
            }
        }
        return "-";
    }

    private static String sqlErrorState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                return String.valueOf(sqlException.getSQLState());
            }
        }
        return "-";
    }

    /** 建池即校验上游版本：非 MySQL 8 直接拒绝，避免"连上了但语义不同"。 */
    private void requireSupportedServer(HikariDataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT VERSION()")) {
            String version = resultSet.next() ? resultSet.getString(1) : null;
            if (!isSupportedVersion(version)) {
                throw exception(AI_CONNECTOR_MYSQL_VERSION_UNSUPPORTED);
            }
        } catch (SQLException failure) {
            throw exception(AI_CONNECTOR_MYSQL_UNAVAILABLE);
        }
    }

    private void evictLeastRecentlyUsedIfFull() {
        while (pools.size() >= MAX_POOLS) {
            String oldest = pools.keySet().iterator().next();
            close(pools.remove(oldest));
        }
    }

    private void close(Pooled pooled) {
        if (pooled == null) {
            return;
        }
        try {
            pooled.dataSource().close();
        } catch (RuntimeException failure) {
            log.warn(
                    "只读连接池关闭异常：connectorId={}, reason={}",
                    pooled.connectorId(),
                    failure.getClass().getSimpleName());
        }
    }

    /** 池条目：连接器编号、数据源与最近使用时间。 */
    private static final class Pooled {

        private final Long connectorId;

        private final HikariDataSource dataSource;

        private long lastUsed;

        private Pooled(Long connectorId, HikariDataSource dataSource, long lastUsed) {
            this.connectorId = connectorId;
            this.dataSource = dataSource;
            this.lastUsed = lastUsed;
        }

        private Long connectorId() {
            return connectorId;
        }

        private HikariDataSource dataSource() {
            return dataSource;
        }

        private long lastUsed() {
            return lastUsed;
        }

        private void touch(long now) {
            this.lastUsed = now;
        }
    }
}
