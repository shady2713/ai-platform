package com.basicframework.module.ai.controller.app.v1.file;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.file.vo.AiFileUploadRespVO;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.file.dto.AiFileUploadResultDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * AI 业务文件接口（A07，应用端）。
 *
 * <p>三件事都走业务 ACL：上传要声明业务类型与业务对象（未知类型拒绝），
 * 读取按**当前**归属判定（无权限与不存在同语义，防编号枚举），
 * 解除引用只允许所有者（管理权限不参与业务判定）。
 * 文件内容的魔数、大小、压缩包安全等校验由 infra 的受控文件接口负责。
 */
@Tag(name = "应用端 - AI 业务文件")
@RestController
@RequestMapping("/ai/file")
@Validated
@RequiredArgsConstructor
public class AiFileController {

    private final AiFileService fileService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传并绑定到业务对象（文件内容与归档安全由 infra 校验）")
    @AuthenticatedOnly
    public CommonResult<AiFileUploadRespVO> upload(
            @Parameter(description = "业务类型（ai_report/ai_knowledge_document/ai_chat_session）", required = true)
                    @RequestParam("businessType")
                    String businessType,
            @Parameter(description = "业务对象标识", required = true) @RequestParam("businessKey") String businessKey,
            @Parameter(description = "文件", required = true) @RequestParam("file") MultipartFile file)
            throws java.io.IOException {
        AiFileUploadResultDTO result = fileService.upload(
                businessType, businessKey, file.getOriginalFilename(), file.getContentType(), file.getBytes());
        return success(new AiFileUploadRespVO()
                .setFileId(result.getFileId())
                .setBusinessType(result.getBusinessType())
                .setBusinessKey(result.getBusinessKey())
                .setName(result.getName())
                .setSize(result.getSize()));
    }

    @GetMapping("/{fileId}")
    @Operation(summary = "按当前归属读取文件内容")
    @AuthenticatedOnly
    public ResponseEntity<byte[]> read(
            @Parameter(description = "文件编号", required = true) @PathVariable("fileId") Long fileId) {
        byte[] content = fileService.read(fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    @DeleteMapping("/{fileId}")
    @Operation(summary = "解除当前主体对该文件的引用（无其他引用时删除文件）")
    @AuthenticatedOnly
    public CommonResult<Boolean> release(
            @Parameter(description = "文件编号", required = true) @PathVariable("fileId") Long fileId) {
        fileService.release(fileId);
        return success(true);
    }
}
