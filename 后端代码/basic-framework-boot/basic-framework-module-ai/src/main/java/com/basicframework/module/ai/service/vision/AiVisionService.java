package com.basicframework.module.ai.service.vision;

import com.basicframework.module.ai.service.vision.dto.AiVisionOcrRequestDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionOcrResultDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionTextResultDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionUnderstandRequestDTO;

/**
 * 图片理解与 OCR 服务（X02）：受控图片输入的**唯一**业务入口。
 *
 * <p>调用链固定为"校验 → 准入 → 读文件 → 核验 → 调用端口"，每一步都在上一步成功后才发生：
 * <ul>
 *   <li><b>形状校验</b>：声明元数据（编号/白名单 MIME/字节数）不合法立即拒绝（零 IO）；</li>
 *   <li><b>能力准入</b>：{@code AiMediaCapabilityGate} 判定端点存在且启用、能力已声明、探测已确认；
 *       失败抛 {@code AI_MODEL_CAPABILITY_NOT_ENABLED} 等稳定错误码，**不读文件、不解析客户端、不外发**；</li>
 *   <li><b>业务 ACL</b>：按当前主体读取私有文件（A07），无权限与不存在同语义；</li>
 *   <li><b>内容核验</b>：字节数/文件头/摘要与声明一致、真实像素在平台上限内；</li>
 *   <li><b>端口调用</b>：构造 X01 冻结的媒体请求并交给准入闸门调用，失败按平台错误码映射。</li>
 * </ul>
 *
 * <p>能力不分开：图片理解与 OCR 是**两个能力、两条方法**（分别声明、分别探测），
 * 本接口不存在"OCR 失败就用理解代替"的实现。
 */
public interface AiVisionService {

    /** 图片理解：返回描述/问答文本。 */
    AiVisionTextResultDTO understandImage(AiVisionUnderstandRequestDTO request);

    /** 图片文字识别（OCR）：返回文本 + 页码/范围/置信度来源。 */
    AiVisionOcrResultDTO recognizeText(AiVisionOcrRequestDTO request);
}
