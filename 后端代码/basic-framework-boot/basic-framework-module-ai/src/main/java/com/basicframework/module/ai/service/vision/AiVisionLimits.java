package com.basicframework.module.ai.service.vision;

/**
 * 图片理解与 OCR 的平台硬上限（X02）。
 *
 * <p>为什么这些数字必须与前端契约逐项一致：`packages/ai-contracts/src/multimodal.ts` 的
 * {@code MULTIMODAL_LIMITS} 是同一套拒绝线（单边 8192、单图 10 MiB、指令 4000 字符、OCR 区域 1000），
 * 只放宽一侧会让"客户端放行、服务端拒绝"或反之，用户看到的行为不可解释。
 *
 * <p>上限一律是**拒绝线**：超限直接拒绝，不截断、不缩放、不降采样后继续。
 * 端点声明的准入范围可以更窄（X01 准入矩阵），本类只表达平台硬上限。
 */
public final class AiVisionLimits {

    /** 图片单边像素上限（与 X01 `MediaValues.MAX_IMAGE_DIMENSION` 一致）。 */
    public static final int MAX_IMAGE_DIMENSION = 8192;

    /** 单张图片字节上限（10 MiB，与前端契约 `maxImageBytes` 一致）。 */
    public static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    /** 图片理解指令长度上限（码元）。 */
    public static final int MAX_INSTRUCTION_LENGTH = 4000;

    /** OCR 语言提示长度上限（BCP-47 形状的短标识）。 */
    public static final int MAX_LANGUAGE_HINT_LENGTH = 16;

    /** 单页 OCR 识别区域数上限（与前端契约 `maxOcrRegions` 一致）。 */
    public static final int MAX_REGIONS_PER_PAGE = 1000;

    /**
     * 单次扫描件 OCR 处理的页数上限。
     *
     * <p>比 K04 解析上限（200 页）更窄：一次 OCR 要为每页渲染图片并调用模型，
     * 200 页的批量调用既超时又难以为失败归因；超限按拒绝处理（提示分批），不做"只处理前 N 页"的静默截断。
     */
    public static final int MAX_DOCUMENT_PAGES = 50;

    /** 单次 OCR 结果的字符总量上限（与 K04 解析上限同量级，超出拒绝而不是截断）。 */
    public static final int MAX_OCR_CHARACTERS = 200_000;

    private AiVisionLimits() {}
}
