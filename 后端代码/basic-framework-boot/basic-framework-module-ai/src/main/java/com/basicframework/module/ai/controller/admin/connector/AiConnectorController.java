package com.basicframework.module.ai.controller.admin.connector;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorPageReqVO;
import com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorProbeRespVO;
import com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorRespVO;
import com.basicframework.module.ai.controller.admin.connector.vo.AiConnectorSaveReqVO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorProbeDO;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorProbeResultDTO;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 连接器管理接口（D01）。
 *
 * <p>权限码与 V66 迁移的菜单种子一一对应：查询 {@code ai:connector:query}、新增 {@code ai:connector:create}、
 * 修改/启停/轮换 {@code ai:connector:update}、删除 {@code ai:connector:delete}、连接测试 {@code ai:connector:probe}。
 * 响应**永不回显秘密**（只有"是否已配置"）；连接测试只返回稳定原因码与耗时。
 */
@Tag(name = "管理后台 - AI 连接器")
@RestController
@RequestMapping("/ai/connector")
@Validated
@RequiredArgsConstructor
public class AiConnectorController {

    private final AiConnectorService connectorService;

    @PostMapping("/create")
    @Operation(summary = "新增连接器（声明式配置 + 秘密加密存储）")
    @PreAuthorize("@ss.hasPermission('ai:connector:create')")
    public CommonResult<Long> create(@Valid @RequestBody AiConnectorSaveReqVO reqVO) {
        return success(connectorService.create(toSaveDTO(reqVO)));
    }

    @PutMapping("/update")
    @Operation(summary = "修改连接器（标识与类型不可修改；留空秘密表示保留）")
    @PreAuthorize("@ss.hasPermission('ai:connector:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody AiConnectorSaveReqVO reqVO) {
        connectorService.update(toSaveDTO(reqVO));
        return success(true);
    }

    @PutMapping("/rotate-credential")
    @Operation(summary = "轮换秘密（只递增秘密版本，旧秘密立即失效）")
    @PreAuthorize("@ss.hasPermission('ai:connector:update')")
    public CommonResult<Boolean> rotateCredential(
            @Parameter(description = "连接器编号", required = true) @RequestParam("id") @NotNull @Positive Long id,
            @Parameter(description = "乐观锁版本", required = true) @RequestParam("version") @NotNull @PositiveOrZero
                    Integer version,
            @Parameter(description = "新秘密", required = true) @RequestParam("credential") @NotNull @Size(max = 1024)
                    String credential) {
        connectorService.rotateCredential(id, version, credential);
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "启用/停用（停用后不允许探测或发起连接）")
    @PreAuthorize("@ss.hasPermission('ai:connector:update')")
    public CommonResult<Boolean> updateStatus(
            @Parameter(description = "连接器编号", required = true) @RequestParam("id") @NotNull @Positive Long id,
            @Parameter(description = "乐观锁版本", required = true) @RequestParam("version") @NotNull @PositiveOrZero
                    Integer version,
            @Parameter(description = "是否启用", required = true) @RequestParam("enabled") @NotNull Boolean enabled) {
        connectorService.updateStatus(id, version, enabled);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除连接器（被数据集/工具引用时拒绝）")
    @PreAuthorize("@ss.hasPermission('ai:connector:delete')")
    public CommonResult<Boolean> delete(
            @Parameter(description = "连接器编号", required = true) @RequestParam("id") @NotNull @Positive Long id,
            @Parameter(description = "乐观锁版本", required = true) @RequestParam("version") @NotNull @PositiveOrZero
                    Integer version) {
        connectorService.delete(id, version);
        return success(true);
    }

    @PostMapping("/{id}/probe")
    @Operation(summary = "连接测试（HTTP 经统一出站策略；结论只记稳定原因码）")
    @PreAuthorize("@ss.hasPermission('ai:connector:probe')")
    public CommonResult<AiConnectorProbeRespVO> probe(
            @Parameter(description = "连接器编号", required = true)
                    @org.springframework.web.bind.annotation.PathVariable("id")
                    @NotNull
                    @Positive
                    Long id) {
        return success(toProbeRespVO(connectorService.probe(id)));
    }

    @GetMapping("/{id}/probe")
    @Operation(summary = "查询探测记录（最新在前）")
    @PreAuthorize("@ss.hasPermission('ai:connector:query')")
    public CommonResult<List<AiConnectorProbeRespVO>> listProbes(
            @Parameter(description = "连接器编号", required = true)
                    @org.springframework.web.bind.annotation.PathVariable("id")
                    @NotNull
                    @Positive
                    Long id) {
        return success(connectorService.listProbes(id).stream()
                .map(AiConnectorController::toProbeRespVO)
                .collect(Collectors.toList()));
    }

    @GetMapping("/get")
    @Operation(summary = "查询连接器（不含秘密与密文）")
    @PreAuthorize("@ss.hasPermission('ai:connector:query')")
    public CommonResult<AiConnectorRespVO> get(
            @Parameter(description = "连接器编号", required = true) @RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(connectorService.getConnector(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "连接器分页")
    @PreAuthorize("@ss.hasPermission('ai:connector:query')")
    public CommonResult<PageResult<AiConnectorRespVO>> page(@Valid AiConnectorPageReqVO reqVO) {
        PageResult<AiConnectorDO> page = connectorService.getPage(reqVO, reqVO.getConnectorType(), reqVO.getStatus());
        return success(new PageResult<>(
                page.getList().stream().map(AiConnectorController::toRespVO).collect(Collectors.toList()),
                page.getTotal()));
    }

    private static AiConnectorSaveDTO toSaveDTO(AiConnectorSaveReqVO reqVO) {
        return new AiConnectorSaveDTO()
                .setId(reqVO.getId())
                .setCode(reqVO.getCode())
                .setName(reqVO.getName())
                .setConnectorType(reqVO.getConnectorType())
                .setConfigJson(reqVO.getConfigJson())
                .setCredential(reqVO.getCredential())
                .setVersion(reqVO.getVersion());
    }

    /** 响应映射：**只回"是否已配置"**，密文与秘密永不出现在响应里。 */
    private static AiConnectorRespVO toRespVO(AiConnectorDO connector) {
        return new AiConnectorRespVO()
                .setId(connector.getId())
                .setCode(connector.getCode())
                .setName(connector.getName())
                .setConnectorType(connector.getConnectorType())
                .setStatus(connector.getStatus())
                .setConfigJson(connector.getConfigJson())
                .setCredentialConfigured(StringUtils.hasText(connector.getCredentialCiphertext()))
                .setCredentialRevision(connector.getCredentialRevision())
                .setReferenced(connector.getReferenced())
                .setVersion(connector.getVersion())
                .setCreateTime(connector.getCreateTime());
    }

    private static AiConnectorProbeRespVO toProbeRespVO(AiConnectorProbeResultDTO result) {
        return new AiConnectorProbeRespVO()
                .setConnectorId(result.getConnectorId())
                .setProbeKind(result.getProbeKind())
                .setStatus(result.getStatus())
                .setDetailCode(result.getDetailCode())
                .setLatencyMs(result.getLatencyMs());
    }

    private static AiConnectorProbeRespVO toProbeRespVO(AiConnectorProbeDO probe) {
        return new AiConnectorProbeRespVO()
                .setConnectorId(probe.getConnectorId())
                .setProbeKind(probe.getProbeKind())
                .setStatus(probe.getStatus())
                .setDetailCode(probe.getDetailCode())
                .setLatencyMs(probe.getLatencyMs());
    }
}
