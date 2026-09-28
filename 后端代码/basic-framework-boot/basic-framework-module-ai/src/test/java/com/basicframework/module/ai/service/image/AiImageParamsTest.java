package com.basicframework.module.ai.service.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import org.junit.jupiter.api.Test;

/**
 * 图片请求参数守卫（X03）：尺寸白名单、输出格式、张数边界、文本与幂等键长度、操作类型收窄。
 *
 * <p>每条拒绝都断言稳定错误码（{@code 1_003_010_000}）：调用方只能依赖错误码解释原因，
 * 响应里没有上游报文；合法取值断言归一化结果（去空白、小写），避免"客户端放行、服务端拒绝"的错位。
 */
class AiImageParamsTest {

    private final AiImageParams params = new AiImageParams();

    @Test
    void acceptsEveryWhitelistedSizeAndNormalizesCaseAndWhitespace() {
        assertThat(AiImageParams.SUPPORTED_SIZES)
                .as("尺寸白名单是显式枚举，档位可审计")
                .containsExactlyInAnyOrder(
                        "256x256",
                        "512x512",
                        "768x768",
                        "1024x1024",
                        "1024x1536",
                        "1536x1024",
                        "1024x1792",
                        "1792x1024");
        for (String size : AiImageParams.SUPPORTED_SIZES) {
            assertThat(params.normalizeSize(size)).isEqualTo(size);
        }
        assertThat(params.normalizeSize("  1024X1536  ")).isEqualTo("1024x1536");
    }

    @Test
    void blankSizeMeansEndpointDefault() {
        assertThat(params.normalizeSize(null)).isNull();
        assertThat(params.normalizeSize("   ")).isNull();
    }

    @Test
    void rejectsSizesOutsideThePlatformWhitelist() {
        assertCode(() -> params.normalizeSize("1x1"), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> params.normalizeSize("1x100000"), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> params.normalizeSize("1024*1024"), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> params.normalizeSize("1024x1024extra"), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> params.normalizeSize("0x0"), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> params.normalizeSize("1024×1024"), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void outputFormatDefaultsToPngAndIsNarrowedToThePortWhitelist() {
        assertThat(AiImageParams.SUPPORTED_FORMATS).containsExactlyInAnyOrder("png", "jpeg", "webp");
        assertThat(params.normalizeFormat(null)).isEqualTo("png");
        assertThat(params.normalizeFormat("")).isEqualTo("png");
        assertThat(params.normalizeFormat("  ")).isEqualTo("png");
        assertThat(params.normalizeFormat(" PNG ")).isEqualTo("png");
        assertThat(params.normalizeFormat("JPEG")).isEqualTo("jpeg");
        assertThat(params.normalizeFormat("WebP")).isEqualTo("webp");

        assertCode(() -> params.normalizeFormat("gif"), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> params.normalizeFormat("bmp"), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> params.normalizeFormat("image/png"), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void countIsBoundedToOneThroughEight() {
        assertThat(AiImageParams.MAX_COUNT).isEqualTo(8);
        assertThat(params.normalizeCount(null)).as("未给张数按 1 张").isEqualTo(1);
        assertThat(params.normalizeCount(1)).isEqualTo(1);
        assertThat(params.normalizeCount(8)).isEqualTo(8);

        assertCode(() -> params.normalizeCount(0), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> params.normalizeCount(-1), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> params.normalizeCount(9), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void textMustBePresentAndWithinColumnWidthAfterTrim() {
        assertThat(AiImageParams.MAX_TEXT_LENGTH).isEqualTo(2000);
        assertThat(params.requireText("  一只猫  ")).as("入库前去掉首尾空白").isEqualTo("一只猫");
        assertThat(params.requireText("猫".repeat(2000))).hasSize(2000);

        assertCode(() -> params.requireText(null), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> params.requireText("   "), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> params.requireText("猫".repeat(2001)), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void requestKeyMustBePresentAndWithinColumnWidthAfterTrim() {
        assertThat(AiImageParams.MAX_REQUEST_KEY_LENGTH).isEqualTo(40);
        assertThat(params.requireRequestKey("  img-1  ")).as("幂等键大小写保留、仅去空白").isEqualTo("img-1");
        assertThat(params.requireRequestKey("k".repeat(40))).hasSize(40);

        assertCode(() -> params.requireRequestKey(null), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> params.requireRequestKey(" "), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> params.requireRequestKey("k".repeat(41)), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void onlyImageOperationsPassTheNarrowingGate() {
        assertThat(params.requireImageOperation(AiMediaTaskDO.OPERATION_GENERATE))
                .isEqualTo(AiMediaTaskDO.OPERATION_GENERATE);
        assertThat(params.requireImageOperation(AiMediaTaskDO.OPERATION_EDIT)).isEqualTo(AiMediaTaskDO.OPERATION_EDIT);

        assertCode(
                () -> params.requireImageOperation(AiMediaTaskDO.OPERATION_TRANSCRIBE),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> params.requireImageOperation(AiMediaTaskDO.OPERATION_SYNTHESIZE),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> params.requireImageOperation(null), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ServiceException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo(expected.getCode());
            assertThat(exception.getMessage()).isEqualTo(expected.getMsg());
        });
    }
}
