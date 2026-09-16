package com.basicframework.module.infra.framework.file.core.client.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.infra.dal.dataobject.file.FileContentDO;
import com.basicframework.module.infra.dal.mysql.file.FileContentMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DBFileClientTest {

    private static final Long CONFIG_ID = 7L;
    private static final String PATH = "reports/result.txt";

    @Mock
    private FileContentMapper fileContentMapper;

    private DBFileClient client;

    @BeforeEach
    void setUp() {
        DBFileClientConfig config = new DBFileClientConfig();
        config.setDomain("https://files.example.com");
        client = new DBFileClient(CONFIG_ID, config, fileContentMapper);
    }

    @Test
    void upload_persistsContentUnderItsOwnConfigAndReturnsDownloadUrl() {
        byte[] content = "file-content".getBytes();

        String url = client.upload(content, PATH, "text/plain");

        ArgumentCaptor<FileContentDO> contentCaptor = ArgumentCaptor.forClass(FileContentDO.class);
        verify(fileContentMapper).insert(contentCaptor.capture());
        assertThat(contentCaptor.getValue().getConfigId()).isEqualTo(CONFIG_ID);
        assertThat(contentCaptor.getValue().getPath()).isEqualTo(PATH);
        assertThat(contentCaptor.getValue().getContent()).isEqualTo(content);
        assertThat(url).isEqualTo("https://files.example.com/admin-api/infra/file/7/get/reports/result.txt");
    }

    @Test
    void getContent_returnsLatestRecordWithoutMutatingMapperList() {
        byte[] oldContent = "old".getBytes();
        byte[] newContent = "new".getBytes();
        List<FileContentDO> immutableRecords = List.of(
                FileContentDO.builder()
                        .id(1L)
                        .configId(CONFIG_ID)
                        .path(PATH)
                        .content(oldContent)
                        .build(),
                FileContentDO.builder()
                        .id(2L)
                        .configId(CONFIG_ID)
                        .path(PATH)
                        .content(newContent)
                        .build());
        when(fileContentMapper.selectListByConfigIdAndPath(CONFIG_ID, PATH)).thenReturn(immutableRecords);

        assertThat(client.getContent(PATH)).isEqualTo(newContent);
    }

    @Test
    void getContent_returnsNullWhenNoRecordExistsAndDeleteStaysConfigScoped() {
        when(fileContentMapper.selectListByConfigIdAndPath(CONFIG_ID, PATH)).thenReturn(List.of());

        assertThat(client.getContent(PATH)).isNull();

        client.delete(PATH);
        verify(fileContentMapper).deleteByConfigIdAndPath(CONFIG_ID, PATH);
    }
}
