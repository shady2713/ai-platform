package com.basicframework.module.ai.service.report.revision;

/**
 * 修订模型端口（R05）：对话修改与"具体怎么调模型"之间的唯一接缝。
 *
 * <p>与 R03 的报表生成模型端口同一取舍：模型只给**操作清单**，服务端负责落结构、查数据与校验。
 * 真实模型不可复现，因此安全语义（操作白名单、展示/数据分类、受控查询）全部用固定夹具离线验证。
 * 生产实现走 M05 的调用编排；未装配时按稳定原因码 {@code AI_REPORT_REVISION_MODEL_UNAVAILABLE} 失败（fail-closed）。
 */
public interface AiReportRevisionModel {

    /**
     * 请求模型给出修订操作清单。
     *
     * @param baseSpecJson 基础版本的 ReportSpec（模型只能引用其中的块与列）
     * @param catalog      可用目录（块、数据集引用与本次授权的数据集；不含数据行）
     * @param instruction  用户的修改指令（自然语言）
     * @return 修订计划 JSON（{@code {"schemaVersion":"1.0","operations":[...]}}）
     */
    String propose(String baseSpecJson, String catalog, String instruction);
}
