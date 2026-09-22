package com.basicframework.module.ai.service.knowledge.ingestion;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_FILE_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_FILE_TOO_LARGE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_FILE_TYPE_UNSUPPORTED;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 入库文件策略（K03）：类型、大小与指纹的唯一判定处（纯函数，可单测）。
 *
 * <p>为什么把类型/大小判定放在服务端而不是信任上传方：请求里的 content-type 与文件名都可以伪造。
 * 这里按**文件扩展名白名单 + 大小上限**做第一道过滤（魔数与压缩展开量由 infra 的受控文件接口负责，
 * 见 F06/A07），并把指纹改成服务端计算（sha256），不接受调用方自报——指纹是幂等与"内容是否变化"的依据。
 *
 * <p>首期支持 FR-17 声明的 TXT / Markdown / 文本型 PDF / DOCX；扫描件 PDF 由 K04 解析后提示需要 OCR。
 */
public final class AiKnowledgeIngestionFilePolicy {

    /** 单文件上限（字节）：与 FR-33 的"上传校验大小"一致，避免把超大文件读进内存。 */
    public static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    /** 支持的文件扩展名（小写，含点）。 */
    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".txt", ".md", ".markdown", ".pdf", ".docx");

    /** 与扩展名对应的可接受 content-type（仅作交叉校验，缺失不拒绝）。 */
    private static final List<String> TEXTUAL_TYPES = List.of(
            "text/plain",
            "text/markdown",
            "text/x-markdown",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/octet-stream");

    private AiKnowledgeIngestionFilePolicy() {}

    /** 校验文件（名称、大小、内容），返回规范化后的文件名。 */
    public static String requireSupportedFile(String fileName, long size, byte[] content) {
        String normalized = StringUtils.hasText(fileName) ? fileName.trim() : null;
        if (normalized == null || normalized.length() > 256) {
            throw exception(AI_KNOWLEDGE_FILE_INVALID);
        }
        if (content == null || content.length == 0 || size <= 0) {
            // 空文件没有可解析的正文：拒绝而不是生成"可用但空"的版本
            throw exception(AI_KNOWLEDGE_FILE_INVALID);
        }
        if (size > MAX_FILE_BYTES) {
            throw exception(AI_KNOWLEDGE_FILE_TOO_LARGE);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String extension = dot < 0 ? "" : lower.substring(dot);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw exception(AI_KNOWLEDGE_FILE_TYPE_UNSUPPORTED);
        }
        return normalized;
    }

    /** 交叉校验 content-type（缺失或通用类型不拒绝，只拒绝明确不支持的声明）。 */
    public static void requireCompatibleContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return;
        }
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        if (TEXTUAL_TYPES.contains(normalized)) {
            return;
        }
        if (normalized.startsWith("text/")) {
            return;
        }
        throw exception(AI_KNOWLEDGE_FILE_TYPE_UNSUPPORTED);
    }

    /** 服务端计算文件指纹（sha256 hex）：幂等与"内容是否变化"的唯一依据。 */
    public static String sha256(byte[] content) {
        if (content == null) {
            throw exception(AI_KNOWLEDGE_FILE_INVALID);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    /** 指纹是否为 sha256 hex（用于校验来自内部链路的指纹）。 */
    public static boolean isSha256(String value) {
        return value != null && value.matches("^[0-9a-f]{64}$");
    }

    /** 文本内容的 UTF-8 字节（供解析器与测试使用）。 */
    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
