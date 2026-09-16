package com.basicframework.module.infra.service.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.basicframework.module.infra.dal.mysql.file.FileContentMapper;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InfraDataIntegrityServiceTest {

    private InfraDataIntegrityService service;

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FileContentMapper fileContentMapper;

    @BeforeEach
    void setUp() {
        service = new InfraDataIntegrityService(fileMapper, fileContentMapper);
    }

    @Test
    void verifyLogicalReferences_returnsStableSummaryWhenClean() {
        assertThat(service.verifyLogicalReferences()).isEqualTo("infra 逻辑引用完整性检查通过");
    }

    @Test
    void verifyLogicalReferences_withOrphanFile_failsWithRelationAndCount() {
        when(fileMapper.selectOrphanFileConfigCount()).thenReturn(3);

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("infra_file.config_id 孤儿引用 3 条");
    }

    @Test
    void verifyLogicalReferences_withOrphanFileContent_failsWithRelationAndCount() {
        when(fileContentMapper.selectOrphanFileConfigCount()).thenReturn(4);

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("infra_file_content.config_id 孤儿引用 4 条");
    }
}
