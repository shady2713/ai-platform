package com.basicframework.module.infra.controller.app.file;

import static com.basicframework.framework.common.pojo.CommonResult.success;
import static com.basicframework.module.infra.framework.file.core.utils.FileMetadataLimits.MAX_DIRECTORY_LENGTH;
import static com.basicframework.module.infra.framework.file.core.utils.FileMetadataLimits.MAX_NAME_LENGTH;
import static com.basicframework.module.infra.framework.file.core.utils.FileMetadataLimits.MAX_TYPE_LENGTH;

import cn.hutool.core.io.IoUtil;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import com.basicframework.module.infra.controller.app.file.vo.AppFileCreateReqVO;
import com.basicframework.module.infra.controller.app.file.vo.AppFilePresignedUrlRespVO;
import com.basicframework.module.infra.controller.app.file.vo.AppFileUploadReqVO;
import com.basicframework.module.infra.enums.file.FileAccessTypeEnum;
import com.basicframework.module.infra.service.file.FileService;
import com.basicframework.module.infra.service.file.FileUploadPrincipal;
import com.basicframework.module.infra.service.file.dto.FilePresignedUrlDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "应用端 - 文件存储")
@RestController
@RequestMapping("/infra/file")
@Validated
@RequiredArgsConstructor
@AuthenticatedOnly
public class AppFileController {

    private final FileService fileService;

    @PostMapping("/upload")
    @Operation(summary = "上传文件", description = "默认私有，需显式声明才公开读取")
    @Parameter(
            name = "file",
            description = "文件附件",
            required = true,
            schema = @Schema(type = "string", format = "binary"))
    public CommonResult<String> uploadFile(@Valid AppFileUploadReqVO uploadReqVO) throws Exception {
        MultipartFile file = uploadReqVO.getFile();
        byte[] content = IoUtil.readBytes(file.getInputStream());
        return success(fileService.createFile(
                content,
                file.getOriginalFilename(),
                uploadReqVO.getDirectory(),
                file.getContentType(),
                currentPrincipal(),
                FileAccessTypeEnum.fromPublicRead(uploadReqVO.isPublicRead())));
    }

    @GetMapping("/presigned-url")
    @Operation(summary = "获取文件预签名地址（上传）", description = "模式二：前端上传文件；默认私有，需显式声明才公开读取")
    @Parameters({
        @Parameter(name = "name", description = "文件名称", required = true),
        @Parameter(name = "directory", description = "文件目录"),
        @Parameter(name = "publicRead", description = "是否允许匿名读取，默认 false")
    })
    public CommonResult<AppFilePresignedUrlRespVO> getFilePresignedUrl(
            @RequestParam("name")
                    @NotBlank(message = "文件名称不能为空")
                    @Size(max = MAX_NAME_LENGTH, message = "文件名称长度不能超过 256 个字符")
                    String name,
            @RequestParam("size") @Positive(message = "文件大小必须大于 0") Long size,
            @RequestParam(value = "type", required = false)
                    @Size(max = MAX_TYPE_LENGTH, message = "文件 MIME 类型长度不能超过 128 个字符")
                    String type,
            @RequestParam(value = "directory", required = false)
                    @Size(max = MAX_DIRECTORY_LENGTH, message = "文件目录长度不能超过 200 个字符")
                    String directory,
            @RequestParam(value = "publicRead", defaultValue = "false") boolean publicRead) {
        FilePresignedUrlDTO presignedUrl = fileService.presignPutUrl(
                name, directory, size, type, currentPrincipal(), FileAccessTypeEnum.fromPublicRead(publicRead));
        return success(new AppFilePresignedUrlRespVO(presignedUrl.getUploadUrl(), presignedUrl.getUploadToken()));
    }

    @PostMapping("/create")
    @Operation(summary = "创建文件", description = "模式二：前端上传文件：配合 presigned-url 接口，记录上传了上传的文件")
    public CommonResult<String> createFile(@Valid @RequestBody AppFileCreateReqVO createReqVO) {
        return success(fileService.createPresignedFile(createReqVO.getUploadToken(), currentPrincipal()));
    }

    private static FileUploadPrincipal currentPrincipal() {
        return new FileUploadPrincipal(WebFrameworkUtils.getLoginUserId(), WebFrameworkUtils.getLoginUserType());
    }
}
