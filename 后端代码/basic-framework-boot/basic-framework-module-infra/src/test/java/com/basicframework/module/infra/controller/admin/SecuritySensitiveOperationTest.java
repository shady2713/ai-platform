package com.basicframework.module.infra.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.infra.controller.admin.config.ConfigController;
import com.basicframework.module.infra.controller.admin.file.FileConfigController;
import com.basicframework.module.infra.controller.admin.job.JobController;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SecuritySensitiveOperationTest {

    @Test
    void controlPlaneConfigurationOperations_requireAuthorization() {
        assertProtectedMethods(
                ConfigController.class,
                "createConfig",
                "updateConfig",
                "deleteConfig",
                "deleteConfigList",
                "getConfig",
                "getConfigPage",
                "export");
        assertProtectedMethods(
                FileConfigController.class,
                "createFileConfig",
                "updateFileConfig",
                "updateFileConfigMaster",
                "deleteFileConfig",
                "deleteFileConfigList",
                "getFileConfig",
                "getFileConfigPage",
                "testFileConfig");
    }

    @Test
    void schedulerControlOperations_requireAuthorization() {
        assertProtectedMethods(
                JobController.class,
                "createJob",
                "updateJob",
                "updateJobStatus",
                "deleteJob",
                "deleteJobList",
                "triggerJob",
                "syncJob");
    }

    private static void assertProtectedMethods(Class<?> controllerType, String... expectedMethods) {
        Set<String> protectedMethods = Arrays.stream(controllerType.getDeclaredMethods())
                .filter(method ->
                        method.isAnnotationPresent(org.springframework.security.access.prepost.PreAuthorize.class)
                                || controllerType.isAnnotationPresent(
                                        com.basicframework.framework.security.core.annotation.AuthenticatedOnly.class))
                .map(method -> method.getName())
                .collect(Collectors.toSet());

        assertThat(protectedMethods).contains(expectedMethods);
    }
}
