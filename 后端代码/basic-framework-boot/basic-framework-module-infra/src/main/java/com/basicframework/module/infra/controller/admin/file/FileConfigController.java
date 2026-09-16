package com.basicframework.module.infra.controller.admin.file;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.module.infra.controller.admin.file.vo.config.FileConfigPageReqVO;
import com.basicframework.module.infra.controller.admin.file.vo.config.FileConfigRespVO;
import com.basicframework.module.infra.controller.admin.file.vo.config.FileConfigSaveReqVO;
import com.basicframework.module.infra.convert.file.FileConfigConvert;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.framework.file.core.client.FileClientConfig;
import com.basicframework.module.infra.framework.file.core.client.s3.S3FileClientConfig;
import com.basicframework.module.infra.service.file.FileConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "管理后台 - 文件配置")
@RestController
@RequestMapping("/infra/file-config")
@Validated
@RequiredArgsConstructor
public class FileConfigController {

    private final FileConfigService fileConfigService;

    @PostMapping("/create")
    @Operation(summary = "创建文件配置")
    @PreAuthorize("@ss.hasPermission('infra:file-config:create')")
    public CommonResult<Long> createFileConfig(@Valid @RequestBody FileConfigSaveReqVO createReqVO) {
        FileConfigDO fileConfig =
                FileConfigConvert.INSTANCE.convert(createReqVO).setId(null);
        return success(fileConfigService.createFileConfig(fileConfig, createReqVO.getConfig()));
    }

    @PutMapping("/update")
    @Operation(summary = "更新文件配置")
    @PreAuthorize("@ss.hasPermission('infra:file-config:update')")
    public CommonResult<Boolean> updateFileConfig(@Valid @RequestBody FileConfigSaveReqVO updateReqVO) {
        fileConfigService.updateFileConfig(FileConfigConvert.INSTANCE.convert(updateReqVO), updateReqVO.getConfig());
        return success(true);
    }

    @PutMapping("/update-master")
    @Operation(summary = "更新文件配置为 Master")
    @PreAuthorize("@ss.hasPermission('infra:file-config:update')")
    public CommonResult<Boolean> updateFileConfigMaster(@RequestParam("id") @Positive Long id) {
        fileConfigService.updateFileConfigMaster(id);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除文件配置")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('infra:file-config:delete')")
    public CommonResult<Boolean> deleteFileConfig(@RequestParam("id") @Positive Long id) {
        fileConfigService.deleteFileConfig(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除文件配置")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('infra:file-config:delete')")
    public CommonResult<Boolean> deleteFileConfigList(@RequestParam("ids") List<Long> ids) {
        fileConfigService.deleteFileConfigList(ids);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得文件配置")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('infra:file-config:query')")
    public CommonResult<FileConfigRespVO> getFileConfig(@RequestParam("id") @Positive Long id) {
        FileConfigDO config = fileConfigService.getFileConfig(id);
        return success(toResponse(config));
    }

    @GetMapping("/page")
    @Operation(summary = "获得文件配置分页")
    @PreAuthorize("@ss.hasPermission('infra:file-config:query')")
    public CommonResult<PageResult<FileConfigRespVO>> getFileConfigPage(@Valid FileConfigPageReqVO pageVO) {
        PageResult<FileConfigDO> pageResult = fileConfigService.getFileConfigPage(
                pageVO, pageVO.getName(), pageVO.getStorage(), pageVO.getCreateTime());
        return success(new PageResult<>(
                pageResult.getList().stream().map(this::toResponse).toList(), pageResult.getTotal()));
    }

    @GetMapping("/test")
    @Operation(summary = "测试文件配置是否正确")
    @PreAuthorize("@ss.hasPermission('infra:file-config:query')")
    public CommonResult<String> testFileConfig(@RequestParam("id") @Positive Long id) throws Exception {
        String url = fileConfigService.testFileConfig(id);
        return success(url);
    }

    private FileConfigRespVO toResponse(FileConfigDO config) {
        if (config == null) {
            return null;
        }
        FileConfigRespVO response = BeanUtils.toBean(config, FileConfigRespVO.class);
        response.setConfig(redactSecret(response, config.getConfig()));
        return response;
    }

    private static FileClientConfig redactSecret(FileConfigRespVO response, FileClientConfig config) {
        if (!(config instanceof S3FileClientConfig source)) {
            return config;
        }
        response.setAccessSecretConfigured(StringUtils.hasText(source.getAccessSecret()));
        S3FileClientConfig redacted = BeanUtils.toBean(source, S3FileClientConfig.class);
        redacted.setAccessSecret(null);
        return redacted;
    }
}
