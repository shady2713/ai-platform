package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** 从实际 Flyway 资源推导迁移断言，避免新增迁移后维护重复的数字常量。 */
final class MigrationTestSupport {

    private static final Pattern VERSION_PATTERN = Pattern.compile("V(\\d+)__.+\\.sql");

    private MigrationTestSupport() {}

    static int migrationCount() {
        return migrationVersions().length;
    }

    static int latestVersion() {
        return Arrays.stream(migrationVersions()).max().orElseThrow();
    }

    static int migrationCountAfter(int version) {
        return (int) Arrays.stream(migrationVersions())
                .filter(candidate -> candidate > version)
                .count();
    }

    private static int[] migrationVersions() {
        try {
            Resource[] resources =
                    new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql");
            assertThat(resources).as("Flyway migration resources").isNotEmpty();
            return Arrays.stream(resources)
                    .map(Resource::getFilename)
                    .mapToInt(MigrationTestSupport::parseVersion)
                    .toArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to enumerate Flyway migration resources", exception);
        }
    }

    private static int parseVersion(String filename) {
        Matcher matcher = VERSION_PATTERN.matcher(filename == null ? "" : filename);
        assertThat(matcher.matches())
                .as("versioned Flyway migration filename: %s", filename)
                .isTrue();
        return Integer.parseInt(matcher.group(1));
    }
}
