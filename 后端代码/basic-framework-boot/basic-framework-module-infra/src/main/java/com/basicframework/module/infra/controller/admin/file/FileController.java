package com.basicframework.module.infra.controller.admin.file;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.pojo.CommonResult.success;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_NOT_EXISTS;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_PATH_INVALID;
import static com.basicframework.module.infra.framework.file.core.utils.FileMetadataLimits.MAX_DIRECTORY_LENGTH;
import static com.basicframework.module.infra.framework.file.core.utils.FileMetadataLimits.MAX_NAME_LENGTH;
import static com.basicframework.module.infra.framework.file.core.utils.FileMetadataLimits.MAX_TYPE_LENGTH;
import static com.basicframework.module.infra.framework.file.core.utils.FileTypeUtils.writeAttachment;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.framework.ratelimiter.core.keyresolver.impl.ClientIpRateLimiterKeyResolver;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.framework.security.core.service.SecurityFrameworkService;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import com.basicframework.module.infra.controller.admin.file.vo.file.*;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.enums.file.FileAccessTypeEnum;
import com.basicframework.module.infra.service.file.FileAccessPrincipal;
import com.basicframework.module.infra.service.file.FileService;
import com.basicframework.module.infra.service.file.FileUploadPrincipal;
import com.basicframework.module.infra.service.file.dto.FilePresignedUrlDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "管理后台 - 文件存储")
@RestController
@RequestMapping("/infra/file")
@Validated
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    private final SecurityFrameworkService securityFrameworkService;

    @PostMapping("/upload")
    @Operation(summary = "上传文件", description = "模式一：后端上传文件；默认私有，需显式声明才公开读取")
    @AuthenticatedOnly
    @Parameter(
            name = "file",
            description = "文件附件",
            required = true,
            schema = @Schema(type = "string", format = "binary"))
    public CommonResult<String> uploadFile(@Valid FileUploadReqVO uploadReqVO) throws Exception {
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
    @AuthenticatedOnly
    @Parameters({
        @Parameter(name = "name", description = "文件名称", required = true),
        @Parameter(name = "directory", description = "文件目录"),
        @Parameter(name = "publicRead", description = "是否允许匿名读取，默认 false")
    })
    public CommonResult<FilePresignedUrlRespVO> getFilePresignedUrl(
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
        return success(new FilePresignedUrlRespVO(presignedUrl.getUploadUrl(), presignedUrl.getUploadToken()));
    }

    @PostMapping("/create")
    @Operation(summary = "创建文件", description = "模式二：前端上传文件：配合 presigned-url 接口，记录上传了上传的文件")
    @AuthenticatedOnly
    public CommonResult<String> createFile(@Valid @RequestBody FileCreateReqVO createReqVO) {
        return success(fileService.createPresignedFile(createReqVO.getUploadToken(), currentPrincipal()));
    }

    @GetMapping("/get")
    @Operation(summary = "获得文件")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('infra:file:query')")
    public CommonResult<FileRespVO> getFile(@RequestParam("id") @Positive Long id) {
        return success(BeanUtils.toBean(fileService.getFile(id), FileRespVO.class));
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除文件")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('infra:file:delete')")
    public CommonResult<Boolean> deleteFile(@RequestParam("id") @Positive Long id) throws Exception {
        fileService.deleteFile(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除文件")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('infra:file:delete')")
    public CommonResult<Boolean> deleteFileList(@RequestParam("ids") List<Long> ids) throws Exception {
        fileService.deleteFileList(ids);
        return success(true);
    }

    @GetMapping("/{configId}/get/**")
    @PermitAll
    @Operation(summary = "下载文件")
    @Parameter(name = "configId", description = "配置编号", required = true)
    // 匿名下载入口限流：120/min/IP 兼顾防批量抓取与列表页图片预览的正常浏览（单页多图逐张请求）
    @RateLimiter(time = 60, count = 120, message = "下载过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    public void getFileContent(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable("configId") @Positive(message = "配置编号必须大于 0") Long configId)
            throws Exception {
        // 获取请求的路径
        String path = StrUtil.subAfter(request.getRequestURI(), "/get/", false);
        if (StrUtil.isEmpty(path)) {
            throw exception(FILE_PATH_INVALID);
        }
        // 解码，解决中文路径的问题
        path = URLUtil.decode(path, StandardCharsets.UTF_8, false);

        // 读取内容
        byte[] content = fileService.getFileContent(configId, path, currentAccessPrincipal(request));
        if (content == null) {
            // 交 GlobalExceptionHandler 统一返回 404 JSON 错误体（ADR 0003）；前端下载封装按 HTTP status 判定
            throw exception(FILE_NOT_EXISTS);
        }
        writeAttachment(response, path, content);
    }

    @GetMapping("/page")
    @Operation(summary = "获得文件分页")
    @PreAuthorize("@ss.hasPermission('infra:file:query')")
    public CommonResult<PageResult<FileRespVO>> getFilePage(@Valid FilePageReqVO pageVO) {
        PageResult<FileDO> pageResult =
                fileService.getFilePage(pageVO, pageVO.getPath(), pageVO.getType(), pageVO.getCreateTime());
        return success(BeanUtils.toBean(pageResult, FileRespVO.class));
    }

    private static FileUploadPrincipal currentPrincipal() {
        return new FileUploadPrincipal(WebFrameworkUtils.getLoginUserId(), WebFrameworkUtils.getLoginUserType());
    }

    private FileAccessPrincipal currentAccessPrincipal(HttpServletRequest request) {
        Integer userType = WebFrameworkUtils.getLoginUserType(request);
        return new FileAccessPrincipal(
                WebFrameworkUtils.getLoginUserId(request),
                userType,
                UserTypeEnum.ADMIN.getValue().equals(userType)
                        && securityFrameworkService.hasPermission("infra:file:query"));
    }
}
