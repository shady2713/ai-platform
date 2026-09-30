package com.basicframework.framework.ai.core.realtime;

/**
 * 实时能力探测（X05 冻结，ADR 0052）：用**真实调用**确认"这个端点的这个协议真的能用"。
 *
 * <p>实现约束（由平台侧验证器与代码评审共同保证）：
 * <ul>
 *   <li>必须发起真实会话协商（最小会话 + 最小音频往返），不得只读配置或返回常量；</li>
 *   <li>必须使用平台内置合成夹具，不使用真实用户数据；</li>
 *   <li>预期内的失败（超时、限流、上游拒绝、协议不支持）返回 {@link RealtimeCapabilityReport}
 *       的 {@code FAILED}/{@code UNSUPPORTED} 结论，不抛异常；只有编程错误才抛异常；</li>
 *   <li>结论只含稳定码与耗时：不记录凭据、提示词、上游报文；</li>
 *   <li>未实现该协议时返回 {@code UNSUPPORTED} + {@link RealtimeCapabilityReport#CODE_ADAPTER_NOT_IMPLEMENTED}，
 *       不得静默返回"支持"。</li>
 * </ul>
 */
public interface RealtimeCapabilityProbe {

    /** 探测一次；结论必须带被探测的协议与配置版本。 */
    RealtimeCapabilityReport probe(RealtimeProbeRequest request);
}
