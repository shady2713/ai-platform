package com.basicframework.module.ai.service.vision.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * OCR 识别范围（X02）：相对图片的归一化坐标（0..1），与前端契约 `ocrRegionSchema.bounds` 同语义。
 *
 * <p>归一化坐标而不是像素坐标：同一份识别结果要在不同缩放比例的界面上回到同一块区域；
 * 像素坐标依赖渲染尺寸，引用会随缩放漂移。
 */
@Data
@Accessors(chain = true)
public class AiVisionRegionDTO {

    /** 左上角横坐标（0..1） */
    private Double x;

    /** 左上角纵坐标（0..1） */
    private Double y;

    /** 宽度（0..1） */
    private Double width;

    /** 高度（0..1） */
    private Double height;

    /** 整页识别范围（覆盖全图）。 */
    public static AiVisionRegionDTO wholePage() {
        return new AiVisionRegionDTO().setX(0d).setY(0d).setWidth(1d).setHeight(1d);
    }
}
