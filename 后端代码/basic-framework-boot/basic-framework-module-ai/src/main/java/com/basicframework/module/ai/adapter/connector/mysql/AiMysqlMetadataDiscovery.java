package com.basicframework.module.ai.adapter.connector.mysql;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_MYSQL_UNAVAILABLE;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 授权元数据发现（D03）：只返回**白名单内**的对象及其列。
 *
 * <p>两层过滤缺一不可：
 * <ol>
 *   <li><b>账号层</b>：只读账号只被授予白名单对象的 SELECT，{@code information_schema} 对未授权对象本就不可见；</li>
 *   <li><b>平台层</b>：发现结果再按连接器白名单过滤（默认拒绝），因此"账号给多了"也不会变成平台可见范围。</li>
 * </ol>
 *
 * <p>发现只读 {@code information_schema}，不执行任何用户 SQL；单个对象的列数有上限，避免宽表把响应撑爆。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiMysqlMetadataDiscovery {

    /** 单对象列数上限。 */
    private static final int MAX_COLUMNS_PER_OBJECT = 200;

    /** 单次发现的对象数上限（白名单本身已 ≤ 20，这里是防御性上限）。 */
    private static final int MAX_OBJECTS = 50;

    private static final String OBJECTS_SQL = "SELECT table_name, table_type FROM information_schema.tables"
            + " WHERE table_schema = ? AND table_type IN ('BASE TABLE', 'VIEW') ORDER BY table_name";

    private static final String COLUMNS_SQL =
            "SELECT column_name, data_type, is_nullable FROM information_schema.columns"
                    + " WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";

    private final AiMysqlPoolRegistry poolRegistry;

    /** 发现授权对象的元数据（白名单为空时直接返回空列表，不连接上游）。 */
    public List<AiMysqlObjectMetadata> discover(AiMysqlConnectionTarget target) {
        if (target.allowedObjects().isEmpty()) {
            return List.of();
        }
        try (Connection connection = poolRegistry.pool(target).getConnection()) {
            List<String[]> visible = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(OBJECTS_SQL)) {
                statement.setString(1, target.database());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next() && visible.size() < MAX_OBJECTS) {
                        visible.add(new String[] {resultSet.getString(1), resultSet.getString(2)});
                    }
                }
            }
            List<AiMysqlObjectMetadata> objects = new ArrayList<>();
            for (String[] row : visible) {
                if (!target.authorizes(target.database(), row[0])) {
                    continue;
                }
                objects.add(new AiMysqlObjectMetadata(
                        target.database(), row[0], toObjectType(row[1]), columns(connection, target, row[0])));
            }
            return objects;
        } catch (SQLException failure) {
            log.warn(
                    "授权元数据发现失败：connectorId={}, reason={}",
                    target.connectorId(),
                    failure.getClass().getSimpleName());
            throw exception(AI_CONNECTOR_MYSQL_UNAVAILABLE);
        }
    }

    private List<AiMysqlObjectMetadata.Column> columns(
            Connection connection, AiMysqlConnectionTarget target, String table) throws SQLException {
        List<AiMysqlObjectMetadata.Column> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(COLUMNS_SQL)) {
            statement.setString(1, target.database());
            statement.setString(2, table);
            statement.setMaxRows(MAX_COLUMNS_PER_OBJECT);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(new AiMysqlObjectMetadata.Column(
                            resultSet.getString(1),
                            resultSet.getString(2),
                            "YES".equalsIgnoreCase(resultSet.getString(3))));
                }
            }
        }
        return columns;
    }

    private static String toObjectType(String tableType) {
        return "VIEW".equalsIgnoreCase(tableType) ? AiMysqlObjectMetadata.TYPE_VIEW : AiMysqlObjectMetadata.TYPE_TABLE;
    }
}
