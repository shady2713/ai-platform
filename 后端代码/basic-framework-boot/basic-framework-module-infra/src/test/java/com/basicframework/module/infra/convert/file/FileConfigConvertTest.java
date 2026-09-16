package com.basicframework.module.infra.convert.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.infra.controller.admin.file.vo.config.FileConfigSaveReqVO;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link FileConfigConvert} 单元测试
 *
 */
class FileConfigConvertTest {

    @Test
    void convert_mapsSaveRequestWithoutPersistenceSecrets() {
        FileConfigSaveReqVO request = new FileConfigSaveReqVO();
        request.setId(5L);
        request.setName("MinIO");
        request.setStorage(10);
        request.setConfig(Map.of("endpoint", "localhost:9000", "accessKey", "secret-key"));
        request.setRemark("测试存储");

        FileConfigDO fileConfig = FileConfigConvert.INSTANCE.convert(request);

        assertThat(fileConfig.getId()).isEqualTo(5L);
        assertThat(fileConfig.getName()).isEqualTo("MinIO");
        assertThat(fileConfig.getStorage()).isEqualTo(10);
        assertThat(fileConfig.getRemark()).isEqualTo("测试存储");
        // 密文与客户端配置对象不允许在转换层落库
        assertThat(fileConfig.getConfig()).isNull();
        assertThat(fileConfig.getConfigCiphertext()).isNull();
        assertThat(fileConfig.getMaster()).isNull();
    }

    @Test
    void convert_nullRequestStaysNull() {
        assertThat(FileConfigConvert.INSTANCE.convert(null)).isNull();
    }
}
