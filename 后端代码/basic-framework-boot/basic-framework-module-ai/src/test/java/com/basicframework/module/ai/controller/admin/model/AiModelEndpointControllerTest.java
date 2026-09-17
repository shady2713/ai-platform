package com.basicframework.module.ai.controller.admin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelEndpointCredentialReqVO;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelEndpointPageReqVO;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelEndpointRespVO;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelEndpointRevisionRespVO;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelEndpointSaveReqVO;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelEndpointStatusReqVO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 模型端点控制器契约：响应永不含凭据（只有 credentialConfigured 标识）、能力按逗号解析、
 * 分页与版本列表映射正确、各管理命令委派到服务层。
 */
class AiModelEndpointControllerTest {

    private final AiModelEndpointService endpointService = mock(AiModelEndpointService.class);

    private final AiModelEndpointController controller = new AiModelEndpointController(endpointService);

    private static AiModelEndpointDO endpoint() {
        return new AiModelEndpointDO()
                .setId(9L)
                .setName("openai-生产")
                .setProvider("openai_compatible")
                .setBaseUrl("https://api.openai.com/v1")
                .setConfigRevision(2)
                .setCredentialRevision(3)
                .setCredentialCiphertext("v1:secret-cipher")
                .setEnabled(true)
                .setReferenced(true)
                .setVersion(4);
    }

    @Test
    void managementCommandsDelegateToService() {
        AiModelEndpointSaveReqVO createReqVO = new AiModelEndpointSaveReqVO();
        createReqVO.setName("openai-生产");
        createReqVO.setProvider("openai_compatible");
        createReqVO.setBaseUrl("https://api.openai.com/v1");
        createReqVO.setModelId("gpt-4o-mini");
        createReqVO.setCapabilities(List.of("TEXT"));
        when(endpointService.createEndpoint(any(AiModelEndpointSaveDTO.class))).thenReturn(9L);
        assertThat(controller.createEndpoint(createReqVO).getData()).isEqualTo(9L);

        // 控制器负责 VO → 服务层命令的映射（服务层不得依赖 VO）
        ArgumentCaptor<AiModelEndpointSaveDTO> dtoCaptor = ArgumentCaptor.forClass(AiModelEndpointSaveDTO.class);
        verify(endpointService).createEndpoint(dtoCaptor.capture());
        assertThat(dtoCaptor.getValue().getModelId()).isEqualTo("gpt-4o-mini");
        assertThat(dtoCaptor.getValue().getCapabilities()).containsExactly("TEXT");

        AiModelEndpointSaveReqVO updateReqVO = new AiModelEndpointSaveReqVO();
        updateReqVO.setId(9L);
        updateReqVO.setVersion(4);
        assertThat(controller.updateEndpoint(updateReqVO).getData()).isTrue();
        verify(endpointService).updateEndpoint(any(AiModelEndpointSaveDTO.class));

        AiModelEndpointStatusReqVO statusReqVO = new AiModelEndpointStatusReqVO();
        statusReqVO.setId(9L);
        statusReqVO.setEnabled(false);
        statusReqVO.setVersion(4);
        assertThat(controller.updateEndpointStatus(statusReqVO).getData()).isTrue();
        verify(endpointService).updateEndpointStatus(9L, 4, false);

        AiModelEndpointCredentialReqVO credentialReqVO = new AiModelEndpointCredentialReqVO();
        credentialReqVO.setId(9L);
        credentialReqVO.setCredential("sk-new");
        credentialReqVO.setVersion(4);
        assertThat(controller.rotateCredential(credentialReqVO).getData()).isTrue();
        verify(endpointService).rotateCredential(9L, 4, "sk-new");

        assertThat(controller.deleteEndpoint(9L, 4).getData()).isTrue();
        verify(endpointService).deleteEndpoint(9L, 4);
    }

    @Test
    void getReturnsConfiguredFlagAndNeverAnyCredentialField() throws Exception {
        when(endpointService.getEndpoint(9L)).thenReturn(endpoint());

        AiModelEndpointRespVO respVO = controller.getEndpoint(9L).getData();

        assertThat(respVO.getId()).isEqualTo(9L);
        assertThat(respVO.getName()).isEqualTo("openai-生产");
        assertThat(respVO.getProvider()).isEqualTo("openai_compatible");
        assertThat(respVO.getBaseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(respVO.getEnabled()).isTrue();
        assertThat(respVO.getReferenced()).isTrue();
        assertThat(respVO.getConfigRevision()).isEqualTo(2);
        assertThat(respVO.getCredentialRevision()).isEqualTo(3);
        assertThat(respVO.getCredentialConfigured()).isTrue();
        assertThat(respVO.getVersion()).isEqualTo(4);

        // 响应类型不得存在任何承载凭据/密文的字段（防止后续演进引入泄漏面）
        List<String> forbidden = Arrays.stream(AiModelEndpointRespVO.class.getDeclaredFields())
                .map(Field::getName)
                .filter(name -> name.toLowerCase().contains("credential")
                        && !name.equals("credentialRevision")
                        && !name.equals("credentialConfigured"))
                .toList();
        assertThat(forbidden).isEmpty();
    }

    @Test
    void pageAndRevisionsMapCorrectly() {
        when(endpointService.getEndpointPage(
                        any(AiModelEndpointPageReqVO.class),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new PageResult<>(List.of(endpoint()), 1L));
        AiModelEndpointPageReqVO pageReqVO = new AiModelEndpointPageReqVO();

        PageResult<AiModelEndpointRespVO> page =
                controller.getEndpointPage(pageReqVO).getData();
        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList()).hasSize(1);
        assertThat(page.getList().get(0).getCredentialConfigured()).isTrue();

        when(endpointService.getRevisions(9L))
                .thenReturn(List.of(
                        new AiModelEndpointRevisionDO()
                                .setEndpointId(9L)
                                .setRevision(2)
                                .setModelId("gpt-4o")
                                .setCapabilities("TEXT,EMBEDDING"),
                        new AiModelEndpointRevisionDO()
                                .setEndpointId(9L)
                                .setRevision(1)
                                .setModelId("gpt-4o-mini")
                                .setCapabilities("")));
        List<AiModelEndpointRevisionRespVO> revisions =
                controller.getRevisions(9L).getData();
        assertThat(revisions).hasSize(2);
        assertThat(revisions.get(0).getCapabilities()).containsExactly("TEXT", "EMBEDDING");
        assertThat(revisions.get(0).getModelId()).isEqualTo("gpt-4o");
        assertThat(revisions.get(1).getCapabilities()).isEmpty();
    }

    @Test
    void resultWrapperCarriesSuccessCode() {
        when(endpointService.getEndpoint(9L)).thenReturn(endpoint());

        CommonResult<AiModelEndpointRespVO> result = controller.getEndpoint(9L);

        assertThat(result.getCode()).isZero();
    }
}
