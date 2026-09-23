package com.basicframework.module.ai.service.report.generation;

/**
 * 报表规格模型端口（R03）：把"需求 + 可用结果目录"变成 ReportSpec 文本。
 *
 * <p>为什么单独成端口：真实模型评测由 Q04/Q10 负责，本卡的确定性用例用**固定模型样例**驱动；
 * 端口只暴露"生成"与"修复"两个动作，实现方（M04 的模型调用）与测试替身都遵守同一契约。
 *
 * <p>提示词里只出现**服务端提供的列目录**（可用结果的实际列与类型），模型无从编造列名之外的东西；
 * 修复请求只回传**稳定错误码**，不回传数据正文。
 */
public interface AiReportSpecModel {

    /** 生成：给定需求与可用结果目录，返回 ReportSpec 文本。 */
    String generate(String requirement, String catalogJson);

    /** 修复：给定上次输出与稳定错误码，返回修正后的 ReportSpec 文本。 */
    String repair(String previousOutput, String errorCode);
}
