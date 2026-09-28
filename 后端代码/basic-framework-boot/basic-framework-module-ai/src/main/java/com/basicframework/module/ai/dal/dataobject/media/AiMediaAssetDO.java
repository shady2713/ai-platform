package com.basicframework.module.ai.dal.dataobject.media;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 媒体产物（X03）：任务产出的私有文件引用与服务端核验过的事实。
 *
 * <p>只存平台私有文件编号（`infra_file` 的唯一读取入口）与**服务端计算**的摘要/字节数/尺寸，
 * 不存上游临时地址、不存厂商响应报文。删产物 = 解除文件引用（A07 释放）后软删本行。
 */
@TableName("ai_media_asset")
@KeySequence("ai_media_asset_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiMediaAssetDO extends SoftDeletableDO {

    /** 产物编号 */
    @TableId
    private Long id;

    /** 媒体任务编号 */
    private Long taskId;

    /** 产物序号（同一任务内从 1 开始） */
    private Integer ordinal;

    /** 平台私有文件编号 */
    private Long fileId;

    /** 产物 MIME（白名单内的取值） */
    private String mimeType;

    /** 产物字节数 */
    private Long sizeBytes;

    /** 产物内容摘要（服务端计算） */
    private String sha256;

    /** 图片宽度（未知为空） */
    private Integer width;

    /** 图片高度（未知为空） */
    private Integer height;

    /** 音频时长（毫秒；未知为空） */
    private Long durationMillis;

    /** 乐观锁版本 */
    private Integer version;
}
