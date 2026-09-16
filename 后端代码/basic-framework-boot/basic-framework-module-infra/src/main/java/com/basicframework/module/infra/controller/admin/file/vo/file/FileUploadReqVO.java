package com.basicframework.module.infra.controller.admin.file.vo.file;

import static com.basicframework.module.infra.framework.file.core.utils.FileMetadataLimits.MAX_DIRECTORY_LENGTH;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Schema(description = "管理后台 - 上传文件 Request VO")
@Data
public class FileUploadReqVO {

    @Schema(description = "文件附件", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "文件附件不能为空")
    private MultipartFile file;

    @Schema(description = "文件目录", example = "XXX/YYY")
    @Size(max = MAX_DIRECTORY_LENGTH, message = "文件目录长度不能超过 200 个字符")
    private String directory;

    @Schema(description = "是否允许匿名读取；默认 false", example = "false")
    private boolean publicRead;

    @AssertTrue(message = "文件目录不正确")
    @JsonIgnore
    public boolean isDirectoryValid() {
        return isDirectoryValid(directory);
    }

    public static boolean isDirectoryValid(String directory) {
        // 1. 不能包含 .. 防止目录穿越
        // 2. 不能以 / 或 \ 开头，防止上传到根目录
        return !StrUtil.contains(directory, "..") && !StrUtil.startWithAny(directory, "/", "\\");
    }
}
