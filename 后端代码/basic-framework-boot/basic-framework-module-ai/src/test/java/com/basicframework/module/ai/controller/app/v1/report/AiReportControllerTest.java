package com.basicframework.module.ai.controller.app.v1.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportPageReqVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportRespVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportSaveReqVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportVersionBriefVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportVersionRespVO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import com.basicframework.module.ai.service.report.persistence.AiReportService;
import com.basicframework.module.ai.service.report.persistence.dto.AiReportSaveDTO;
import jakarta.annotation.security.PermitAll;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * R04 报表应用端控制面契约：每个端点有且只有一种鉴权策略；协议层不出现归属与范围指纹字段
 * （归属由服务端会话决定，指纹是内部授权凭据）。
 */
class AiReportControllerTest {

    private final AiReportService reportService = mock(AiReportService.class);

    private final AiReportController controller = new AiReportController(reportService);

    private static AiReportDO report() {
        AiReportDO report = new AiReportDO()
                .setId(71L)
                .setCode("sales_overview")
                .setName("销售总览")
                .setDescription("说明")
                .setMode(AiReportDO.MODE_SNAPSHOT)
                .setServiceId(5L)
                .setReleaseId(6L)
                .setThemeId("default")
                .setThemeRevision(2)
                .setSchemaVersion("1.0")
                .setLatestVersionNo(2)
                .setPublishedVersionNo(2)
                .setVersion(1);
        // BaseDO 的链式 setter 返回声明类，审计时间只能在链外设置
        report.setCreateTime(LocalDateTime.of(2026, 9, 23, 10, 0));
        report.setUpdateTime(LocalDateTime.of(2026, 9, 23, 11, 0));
        return report;
    }

    private static AiReportVersionDO version() {
        AiReportVersionDO version = new AiReportVersionDO()
                .setId(81L)
                .setReportId(71L)
                .setVersionNo(2)
                .setMode(AiReportDO.MODE_SNAPSHOT)
                .setSpecJson("{\"schemaVersion\":\"1.0\"}")
                .setDataJson("{\"blocks\":[]}")
                .setSourcesJson("[{\"resourceType\":\"KNOWLEDGE_BASE\",\"resourceKey\":\"kb-1\"}]")
                .setScopeRefsJson("[{\"resourceType\":\"KNOWLEDGE_BASE\",\"resourceKey\":\"kb-1\"}]")
                .setScopeFingerprint("f".repeat(64))
                .setAsOf(LocalDateTime.of(2026, 9, 23, 9, 30))
                .setCompleteness("COMPLETE")
                .setCreatedByRun("run_0123456789abcdef01234567");
        version.setCreateTime(LocalDateTime.of(2026, 9, 23, 9, 30));
        return version;
    }

    private static AiReportSaveReqVO saveReq() {
        return new AiReportSaveReqVO()
                .setId(null)
                .setCode("sales_overview")
                .setName("销售总览")
                .setDescription("说明")
                .setMode(AiReportDO.MODE_SNAPSHOT)
                .setServiceId(5L)
                .setReleaseId(6L)
                .setThemeId("default")
                .setThemeRevision(2)
                .setSchemaVersion("1.0")
                .setSpecJson("{\"schemaVersion\":\"1.0\"}")
                .setDataJson("{\"blocks\":[]}")
                .setSourcesJson("[{\"resourceType\":\"KNOWLEDGE_BASE\",\"resourceKey\":\"kb-1\"}]")
                .setCompleteness("COMPLETE")
                .setCreatedByRun("run_0123456789abcdef01234567");
    }

    @Test
    void everyEndpointDeclaresExactlyOneAuthenticationStrategy() {
        int endpoints = 0;
        for (Method method : AiReportController.class.getDeclaredMethods()) {
            if (method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class) == null
                    && method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class) == null) {
                continue;
            }
            endpoints++;
            int strategies = 0;
            strategies += method.isAnnotationPresent(AuthenticatedOnly.class) ? 1 : 0;
            strategies += method.isAnnotationPresent(PermitAll.class) ? 1 : 0;
            strategies += method.isAnnotationPresent(PreAuthorize.class) ? 1 : 0;
            assertThat(strategies).as("%s 必须且只能声明一种鉴权策略", method.getName()).isEqualTo(1);
            // 报表端点一律"登录即可"，归属与范围在服务层判定
            assertThat(method.isAnnotationPresent(AuthenticatedOnly.class)).isTrue();
        }
        assertThat(endpoints).as("必须扫描到报表端点（否则本契约形同虚设）").isEqualTo(6);
    }

    @Test
    void requestAndResponseVosExposeNoOwnershipOrFingerprintFields() {
        List<Class<?>> vos = List.of(
                AiReportSaveReqVO.class,
                AiReportRespVO.class,
                AiReportVersionRespVO.class,
                AiReportVersionBriefVO.class,
                AiReportPageReqVO.class);
        for (Class<?> vo : vos) {
            assertThat(Arrays.stream(vo.getDeclaredFields()).map(Field::getName).toList())
                    .as("%s 不能出现归属或范围指纹字段", vo.getSimpleName())
                    .doesNotContain(
                            "applicationId", "subjectType", "externalUserId", "scopeFingerprint", "scopeRefsJson");
        }
    }

    @Test
    void saveCreatesWithoutIdAndAppendsVersionWithId() {
        when(reportService.create(any(AiReportSaveDTO.class))).thenReturn(71L);
        assertThat(controller.save(saveReq()).getData()).isEqualTo(71L);
        verify(reportService, never()).saveVersion(any(AiReportSaveDTO.class));

        when(reportService.saveVersion(any(AiReportSaveDTO.class))).thenReturn(71L);
        assertThat(controller.save(saveReq().setId(71L).setVersion(1)).getData())
                .isEqualTo(71L);
        verify(reportService).saveVersion(any(AiReportSaveDTO.class));
    }

    @Test
    void getPageVersionsAndVersionMapServiceResults() {
        when(reportService.getReport(71L)).thenReturn(report());
        AiReportRespVO respVO = controller.get(71L).getData();
        assertThat(respVO.getCode()).isEqualTo("sales_overview");
        assertThat(respVO.getLatestVersionNo()).isEqualTo(2);
        assertThat(respVO.getVersion()).isEqualTo(1);

        when(reportService.getReportPage(any(AiReportPageReqVO.class), eq(AiReportDO.MODE_SNAPSHOT)))
                .thenReturn(new PageResult<>(List.of(report()), 1L));
        PageResult<AiReportRespVO> page = controller
                .page(new AiReportPageReqVO().setMode(AiReportDO.MODE_SNAPSHOT))
                .getData();
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getList()).hasSize(1);

        when(reportService.listVersions(71L)).thenReturn(List.of(version()));
        List<AiReportVersionBriefVO> briefs = controller.versions(71L).getData();
        assertThat(briefs).hasSize(1);
        assertThat(briefs.get(0).getVersionNo()).isEqualTo(2);
        assertThat(briefs.get(0).getCompleteness()).isEqualTo("COMPLETE");

        when(reportService.getVersion(71L, 2)).thenReturn(version());
        AiReportVersionRespVO byVersion = controller.version(71L, 2).getData();
        assertThat(byVersion.getReportId()).isEqualTo(71L);
        assertThat(byVersion.getSpecJson()).contains("schemaVersion");
        assertThat(byVersion.getDataJson()).contains("blocks");
        assertThat(byVersion.getCreatedByRun()).startsWith("run_");
    }

    @Test
    void currentReturnsPublishedVersionWithoutFingerprint() {
        when(reportService.readCurrent(71L)).thenReturn(version());
        AiReportVersionRespVO respVO = controller.current(71L).getData();
        assertThat(respVO.getVersionNo()).isEqualTo(2);
        assertThat(respVO.getAsOf()).isNotNull();
        // 内部指纹不回显
        assertThat(Arrays.stream(AiReportVersionRespVO.class.getDeclaredFields())
                        .map(Field::getName)
                        .toList())
                .doesNotContain("scopeFingerprint");
    }
}
