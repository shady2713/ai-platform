package com.basicframework.module.infra.controller.admin.file.vo.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@link FileUploadReqVO} 单元测试
 *
 */
class FileUploadReqVOTest {

    @Test
    void directoryValidation_acceptsSafeRelativePaths() {
        assertThat(FileUploadReqVO.isDirectoryValid("uploads/2026/08")).isTrue();
        assertThat(FileUploadReqVO.isDirectoryValid("")).isTrue();
        assertThat(FileUploadReqVO.isDirectoryValid(null)).isTrue();
    }

    @Test
    void directoryValidation_rejectsTraversalAndRootPaths() {
        assertThat(FileUploadReqVO.isDirectoryValid("a/../b")).isFalse();
        assertThat(FileUploadReqVO.isDirectoryValid("../etc")).isFalse();
        assertThat(FileUploadReqVO.isDirectoryValid("dir/..")).isFalse();
        assertThat(FileUploadReqVO.isDirectoryValid("/etc")).isFalse();
        assertThat(FileUploadReqVO.isDirectoryValid("\\windows")).isFalse();
    }

    @Test
    void beanInstance_mirrorsStaticDirectoryValidation() {
        FileUploadReqVO vo = new FileUploadReqVO();
        vo.setFile(mock(MultipartFile.class));
        vo.setDirectory("uploads/2026");
        vo.setPublicRead(true);

        assertThat(vo.getFile()).isNotNull();
        assertThat(vo.getDirectory()).isEqualTo("uploads/2026");
        assertThat(vo.isPublicRead()).isTrue();
        assertThat(vo.isDirectoryValid()).isTrue();

        vo.setDirectory("../escape");
        assertThat(vo.isDirectoryValid()).isFalse();
    }
}
