package com.basicframework.module.infra.controller.admin.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.controller.admin.file.vo.config.FileConfigPageReqVO;
import com.basicframework.module.infra.controller.admin.file.vo.config.FileConfigRespVO;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.framework.file.core.client.s3.S3FileClientConfig;
import com.basicframework.module.infra.service.file.FileConfigService;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileConfigControllerTest {

    @Test
    void getFileConfig_redactsAccessSecretAndReturnsConfigurationState() {
        FileConfigService service = mock(FileConfigService.class);
        FileConfigController controller = new FileConfigController(service);
        S3FileClientConfig clientConfig = new S3FileClientConfig();
        clientConfig.setAccessSecret("runtime-secret");
        when(service.getFileConfig(1L)).thenReturn(new FileConfigDO().setId(1L).setConfig(clientConfig));

        FileConfigRespVO response = controller.getFileConfig(1L).getData();

        assertThat(response.getAccessSecretConfigured()).isTrue();
        assertThat(((S3FileClientConfig) response.getConfig()).getAccessSecret())
                .isNull();
        assertThat(clientConfig.getAccessSecret()).isEqualTo("runtime-secret");
    }

    @Test
    void getFileConfigPage_redactsEverySecretAndPreservesTotal() {
        FileConfigService service = mock(FileConfigService.class);
        FileConfigController controller = new FileConfigController(service);
        FileConfigPageReqVO request = new FileConfigPageReqVO();
        S3FileClientConfig clientConfig = new S3FileClientConfig();
        clientConfig.setAccessSecret("runtime-secret");
        when(service.getFileConfigPage(request, null, null, null))
                .thenReturn(
                        new PageResult<>(List.of(new FileConfigDO().setId(1L).setConfig(clientConfig)), 1L));

        PageResult<FileConfigRespVO> response =
                controller.getFileConfigPage(request).getData();

        assertThat(response.getTotal()).isEqualTo(1L);
        assertThat(response.getList()).singleElement().satisfies(item -> {
            assertThat(item.getAccessSecretConfigured()).isTrue();
            assertThat(((S3FileClientConfig) item.getConfig()).getAccessSecret())
                    .isNull();
        });
    }
}
