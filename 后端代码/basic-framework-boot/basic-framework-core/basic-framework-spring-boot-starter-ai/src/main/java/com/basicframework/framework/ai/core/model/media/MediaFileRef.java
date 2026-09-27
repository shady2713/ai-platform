package com.basicframework.framework.ai.core.model.media;

/**
 * 媒体输入文件引用（X01 冻结）：只标识平台私有文件与声明级元数据，不携带字节、公网 URL 或厂商类型。
 *
 * <p>调用方必须先按业务 ACL 确认调用者有权读取该文件，再把引用交给模型端口；端口实现按
 * {@code fileId} 取字节并外发。厂商临时 URL 不得作为输入地址：地址与内容都不受平台控制，
 * 也无法保证"无隐藏外发"。
 *
 * <p>准入矩阵里的"输入文件要求"（格式白名单、单文件上限、时长上限、张数上限）由端点声明；
 * 本类型只保证元数据自洽，不代表某端点接受该文件。
 *
 * @param fileId    平台私有文件编号，必须为正数
 * @param mimeType  文件 MIME 类型（去空白并小写归一化）
 * @param sizeBytes 文件字节数，必须为正数
 * @param sha256    文件内容摘要（小写 64 位十六进制）；可为空，用于外发前的完整性核对
 */
public record MediaFileRef(Long fileId, String mimeType, long sizeBytes, String sha256) {

    public MediaFileRef {
        if (fileId == null || fileId <= 0) {
            throw new IllegalArgumentException("媒体文件编号必须为正数");
        }
        mimeType = MediaValues.requireMimeType(mimeType, "媒体文件 MIME 类型");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("媒体文件字节数必须为正数");
        }
        sha256 = MediaValues.requireSha256(sha256, "媒体文件摘要");
    }

    /** 构造不带摘要的文件引用（摘要由读取方按内容核对）。 */
    public static MediaFileRef of(Long fileId, String mimeType, long sizeBytes) {
        return new MediaFileRef(fileId, mimeType, sizeBytes, null);
    }
}
