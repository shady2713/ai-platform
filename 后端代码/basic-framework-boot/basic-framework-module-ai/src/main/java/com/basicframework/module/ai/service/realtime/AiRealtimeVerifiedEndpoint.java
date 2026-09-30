package com.basicframework.module.ai.service.realtime;

import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.realtime.RealtimeAdapter;
import com.basicframework.framework.ai.core.realtime.RealtimeAudioFormat;
import com.basicframework.framework.ai.core.realtime.RealtimeProtocol;

/**
 * 已验证的实时端点事实（X05）：会话受理前必须拿到的全部"已确认可用"输入。
 *
 * <p>{@code snapshot} 携带解密后的凭据，只允许存在于受控内存（toString 已脱敏）；
 * 其余字段都是受理时写入会话行的固定事实。
 */
public record AiRealtimeVerifiedEndpoint(
        Long endpointId,
        Integer configRevision,
        Integer credentialRevision,
        String modelRef,
        RealtimeProtocol protocol,
        RealtimeAudioFormat audioFormat,
        ModelEndpointSnapshot snapshot,
        RealtimeAdapter adapter) {

    /** 脱敏输出：凭据由 {@link ModelEndpointSnapshot#toString()} 保证不出现。 */
    @Override
    public String toString() {
        return "AiRealtimeVerifiedEndpoint[endpointId=" + endpointId + ", configRevision=" + configRevision
                + ", protocol=" + protocol + ", audioFormat=" + audioFormat + ", snapshot=" + snapshot + "]";
    }
}
