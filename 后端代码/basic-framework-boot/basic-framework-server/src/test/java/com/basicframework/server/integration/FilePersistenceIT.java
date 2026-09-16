package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import com.basicframework.module.infra.framework.file.core.enums.FileStorageEnum;
import com.basicframework.module.infra.job.InfraDataIntegrityAuditJob;
import com.basicframework.module.infra.service.file.FileConfigService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** 使用真实 MySQL 与数据库文件存储验证文件配置引用、审计与物理删除。 */
class FilePersistenceIT extends AbstractPersistenceIntegrationTest {

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private InfraDataIntegrityAuditJob infraDataIntegrityAuditJob;

    @Autowired
    private FileConfigService fileConfigService;

    @Autowired
    private SqlSessionTemplate sqlSessionTemplate;

    @Test
    void fileConfigurationAndHardDelete_succeedAgainstRealServices() throws Exception {
        verifyFileConfigReferenceIntegrity();
        verifyHardDeletePersistence();
    }

    private void verifyFileConfigReferenceIntegrity() throws Exception {
        verifyFileConfigSchemaConstraints();
        verifyFileConfigTestUploadIsManaged();
        Long configId = createReferencedFileConfig();
        verifyFileConfigDeleteAndForeignKeyProtection(configId);
        verifyFileConfigSoftDeleteAudits(configId);
    }

    private void verifyFileConfigSchemaConstraints() {
        assertThat(jdbcTemplate.queryForList(
                        """
                        SELECT constraint_name
                        FROM information_schema.referential_constraints
                        WHERE constraint_schema = DATABASE()
                          AND constraint_name IN ('fk_file_config', 'fk_file_content_config')
                          AND delete_rule = 'RESTRICT'
                        ORDER BY constraint_name
                        """,
                        String.class))
                .containsExactly("fk_file_config", "fk_file_content_config");
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM information_schema.statistics
                        WHERE table_schema = DATABASE()
                          AND table_name IN ('infra_file', 'infra_file_content')
                          AND index_name = 'idx_config_id'
                          AND column_name = 'config_id'
                        """,
                        Integer.class))
                .isEqualTo(2);
    }

    private void verifyFileConfigTestUploadIsManaged() throws Exception {
        Long configId = createDatabaseFileConfig("integration-db-test");
        assertThat(fileConfigService.testFileConfig(configId)).startsWith("http://localhost/files/");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM infra_file WHERE config_id = ?", Integer.class, configId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM infra_file_content WHERE config_id = ?", Integer.class, configId))
                .isEqualTo(1);
        jdbcTemplate.update("DELETE FROM infra_file_content WHERE config_id = ?", configId);
        jdbcTemplate.update("DELETE FROM infra_file WHERE config_id = ?", configId);
        fileConfigService.deleteFileConfig(configId);
    }

    private Long createReferencedFileConfig() {
        Long configId = createDatabaseFileConfig("integration-db-reference");
        jdbcTemplate.update(
                """
                INSERT INTO infra_file (id, config_id, name, path, url, type, size)
                VALUES (?, ?, 'integration.txt', 'integration/file-config.txt',
                        'http://localhost/files/integration/file-config.txt', 'text/plain', 1)
                """,
                9_075_001L,
                configId);
        jdbcTemplate.update(
                "INSERT INTO infra_file_content (id, config_id, path, content) VALUES (?, ?, ?, ?)",
                9_075_001L,
                configId,
                "integration/file-config.txt",
                new byte[] {1});
        return configId;
    }

    private Long createDatabaseFileConfig(String name) {
        return fileConfigService.createFileConfig(
                new FileConfigDO().setName(name).setStorage(FileStorageEnum.DB.getStorage()),
                Map.of("domain", "http://localhost/files"));
    }

    private void verifyFileConfigDeleteAndForeignKeyProtection(Long configId) {
        assertServiceException(
                com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_CONFIG_IN_USE.getCode(),
                () -> fileConfigService.deleteFileConfig(configId));
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM infra_file_config WHERE id = ?", configId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO infra_file (config_id, path, url, size) VALUES (?, 'missing', 'missing', 1)",
                        9_075_999L))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO infra_file_content (config_id, path, content) VALUES (?, 'missing', ?)",
                        9_075_999L,
                        new byte[] {1}))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void verifyFileConfigSoftDeleteAudits(Long configId) {
        jdbcTemplate.update("UPDATE infra_file_config SET deleted = b'1' WHERE id = ?", configId);
        assertThatThrownBy(() -> infraDataIntegrityAuditJob.execute(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("infra_file.config_id 孤儿引用 1 条");

        jdbcTemplate.update("UPDATE infra_file_config SET deleted = b'0' WHERE id = ?", configId);
        jdbcTemplate.update("DELETE FROM infra_file WHERE config_id = ?", configId);
        jdbcTemplate.update("UPDATE infra_file_config SET deleted = b'1' WHERE id = ?", configId);
        sqlSessionTemplate.clearCache();
        assertThatThrownBy(() -> infraDataIntegrityAuditJob.execute(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("infra_file_content.config_id 孤儿引用 1 条");

        jdbcTemplate.update("UPDATE infra_file_config SET deleted = b'0' WHERE id = ?", configId);
        jdbcTemplate.update("DELETE FROM infra_file_content WHERE config_id = ?", configId);
        sqlSessionTemplate.clearCache();
        fileConfigService.deleteFileConfig(configId);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT deleted FROM infra_file_config WHERE id = ?", Boolean.class, configId))
                .isTrue();
        assertThat(infraDataIntegrityAuditJob.execute("")).isEqualTo("infra 逻辑引用完整性检查通过");
    }

    private void verifyHardDeletePersistence() {
        FileDO file = new FileDO();
        file.setName("integration-hard-delete.txt");
        file.setPath("integration/hard-delete.txt");
        file.setUrl("http://localhost/integration/hard-delete.txt");
        file.setType("text/plain");
        file.setSize(1L);
        fileMapper.insert(file);

        assertThat(file.getId()).isPositive();
        assertThat(fileMapper.deleteById(file.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM infra_file WHERE id = ?", Integer.class, file.getId()))
                .isZero();
    }
}
