package com.basicframework.module.ai.service.realtime;

import com.basicframework.framework.ai.core.realtime.RealtimeAdapter;
import com.basicframework.framework.ai.core.realtime.RealtimeProtocol;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 实时适配器注册表（X05）：平台装配期收集适配器 Bean，一个协议只允许一个适配器。
 *
 * <p>为什么空注册表是**合法**状态：本环境没有真实供应商的凭据与出网通道，平台不提供任何
 * "看起来能跑"的默认适配器（ADR 0052 的未验证项）。没有适配器 ⇒ 该协议无法验证 ⇒ 会话受理
 * 按 {@code AI_REALTIME_ADAPTER_UNAVAILABLE} 拒绝——这正是"不假设供应商一致"的运行时落点。
 *
 * <p>同一协议注册两个适配器是装配错误（平台不做候选遍历与故障转移），启动即失败。
 */
@Component
public class AiRealtimeAdapterRegistry {

    private final Map<RealtimeProtocol, RealtimeAdapter> adapters;

    public AiRealtimeAdapterRegistry(List<RealtimeAdapter> adapterBeans) {
        Map<RealtimeProtocol, RealtimeAdapter> discovered = new EnumMap<>(RealtimeProtocol.class);
        for (RealtimeAdapter adapter : adapterBeans == null ? List.<RealtimeAdapter>of() : adapterBeans) {
            RealtimeAdapter previous = discovered.put(adapter.protocol(), adapter);
            if (previous != null) {
                throw new IllegalStateException("同一协议注册了多个实时适配器：" + adapter.protocol() + "（"
                        + previous.getClass().getName() + " / "
                        + adapter.getClass().getName() + "）");
            }
        }
        this.adapters = Map.copyOf(discovered);
    }

    /** 按协议查找适配器（未注册返回空；调用方按稳定原因拒绝，不回退其它协议）。 */
    public Optional<RealtimeAdapter> find(RealtimeProtocol protocol) {
        if (protocol == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(adapters.get(protocol));
    }

    /** 已注册协议集合（排障与验证台账展示用）。 */
    public Set<RealtimeProtocol> registeredProtocols() {
        return adapters.keySet();
    }
}
