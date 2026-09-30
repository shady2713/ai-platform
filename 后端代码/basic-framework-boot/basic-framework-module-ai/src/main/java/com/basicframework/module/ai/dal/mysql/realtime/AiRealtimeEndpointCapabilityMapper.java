package com.basicframework.module.ai.dal.mysql.realtime;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeEndpointCapabilityDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 实时端点能力验证 Mapper（X05）：按（端点, 配置版本, 协议）唯一，探测结论幂等写入。
 *
 * <p>结论覆盖写（{@code ON DUPLICATE KEY UPDATE}）：同一（端点, 配置版本, 协议）只保留**最新**
 * 结论，历史不参与判定；配置版本变化自然形成新行，旧行因版本不匹配而失效（不是"读最新一条"，
 * 而是"读当前配置版本那一条"）。
 */
@Mapper
public interface AiRealtimeEndpointCapabilityMapper extends BaseMapperX<AiRealtimeEndpointCapabilityDO> {

    /** 当前配置版本在指定协议上的验证结论（无记录返回空）。 */
    default AiRealtimeEndpointCapabilityDO selectOneByVersion(
            Long endpointId, Integer configRevision, String protocol) {
        if (endpointId == null || configRevision == null || protocol == null) {
            return null;
        }
        return selectOne(new LambdaQueryWrapperX<AiRealtimeEndpointCapabilityDO>()
                .eq(AiRealtimeEndpointCapabilityDO::getEndpointId, endpointId)
                .eq(AiRealtimeEndpointCapabilityDO::getConfigRevision, configRevision)
                .eq(AiRealtimeEndpointCapabilityDO::getProtocol, protocol));
    }

    /** 写入/覆盖验证结论（同版本同协议只留最新）。 */
    @Insert("INSERT INTO ai_realtime_endpoint_capability (endpoint_id, config_revision, protocol, status,"
            + " detail_code, declared_capabilities, confirmed_capabilities, audio_formats, latency_millis,"
            + " probed_time, version, creator, create_time, updater, update_time)"
            + " VALUES (#{endpointId}, #{configRevision}, #{protocol}, #{status}, #{detailCode},"
            + " #{declaredCapabilities}, #{confirmedCapabilities}, #{audioFormats}, #{latencyMillis},"
            + " #{probedTime}, 0, #{creator}, NOW(), #{creator}, NOW())"
            + " ON DUPLICATE KEY UPDATE status = VALUES(status), detail_code = VALUES(detail_code),"
            + " declared_capabilities = VALUES(declared_capabilities),"
            + " confirmed_capabilities = VALUES(confirmed_capabilities), audio_formats = VALUES(audio_formats),"
            + " latency_millis = VALUES(latency_millis), probed_time = VALUES(probed_time),"
            + " version = version + 1, updater = VALUES(updater), update_time = NOW(), deleted = b'0'")
    int upsert(
            @Param("endpointId") Long endpointId,
            @Param("configRevision") Integer configRevision,
            @Param("protocol") String protocol,
            @Param("status") String status,
            @Param("detailCode") String detailCode,
            @Param("declaredCapabilities") String declaredCapabilities,
            @Param("confirmedCapabilities") String confirmedCapabilities,
            @Param("audioFormats") String audioFormats,
            @Param("latencyMillis") long latencyMillis,
            @Param("probedTime") java.time.LocalDateTime probedTime,
            @Param("creator") String creator);
}
