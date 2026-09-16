package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 生产配置下的可执行 jar 启动冒烟测试。
 *
 * <p>该测试从 Maven {@code package} 阶段生成的 fat jar 启动独立 JVM，并以真实 MySQL、Redis、Flyway 与 HTTP 健康端点作为
 * 可观察边界。测试只使用临时容器凭据，子进程输出不包含启动参数，失败时仅附带有界日志尾部。
 */
@Testcontainers
class PackagedJarBootSmokeIT {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final String DATABASE_NAME = "basic_framework";
    private static final String DATABASE_USERNAME = "framework_app";
    private static final String DATABASE_PASSWORD = "integration-only-db";
    private static final String FLYWAY_USERNAME = "framework_migrator";
    private static final String FLYWAY_PASSWORD = "integration-only-flyway";
    private static final String MYSQL_ROOT_PASSWORD = "integration-only-root";
    private static final String REDIS_PASSWORD = "integration-only-redis";
    private static final String CREDENTIAL_ENCRYPTION_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final int EXPECTED_MIGRATION_COUNT = MigrationTestSupport.migrationCount();
    private static final int EXPECTED_LATEST_MIGRATION = MigrationTestSupport.latestVersion();

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
                            "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername("root")
            .withPassword(MYSQL_ROOT_PASSWORD)
            .withInitScript("boot-smoke/mysql-init.sql");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
                    "redis:7.4.11@sha256:71da9275c5f3fcb97d0fa0c8c5b36cc995327265420f17a04bfd544f458059f7"))
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    @Test
    void packagedJar_startsWithProductionConfigurationAndRealInfrastructure() throws Exception {
        Path jar = locatePackagedJar();
        ProcessOutput processOutput = new ProcessOutput();
        Process process = startApplication(jar, processOutput);

        try {
            awaitHealthyResponse(process, processOutput);
            assertFlywayMigratedEmptyDatabase();
            assertRedisAcceptsAuthenticatedCommands();
        } finally {
            stopApplication(process);
            processOutput.awaitCompletion();
        }
    }

    private static Path locatePackagedJar() throws Exception {
        Path codeSource = Path.of(PackagedJarBootSmokeIT.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toAbsolutePath();
        Path targetDirectory =
                codeSource.getFileName().toString().equals("test-classes") ? codeSource.getParent() : codeSource;
        Path jar = targetDirectory.resolve("basic-framework-server.jar");
        assertThat(jar).as("Spring Boot repackage output").isRegularFile();
        return jar;
    }

    private static Process startApplication(Path jar, ProcessOutput processOutput) throws IOException {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        List<String> command = List.of(
                javaExecutable.toString(),
                "-jar",
                jar.toString(),
                "--server.port=0",
                "--spring.profiles.active=prod",
                "--spring.datasource.dynamic.datasource.master.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.dynamic.datasource.master.username=" + DATABASE_USERNAME,
                "--spring.datasource.dynamic.datasource.master.password=" + DATABASE_PASSWORD,
                "--spring.datasource.dynamic.datasource.master.name=" + DATABASE_NAME,
                "--spring.flyway.url=" + MYSQL.getJdbcUrl(),
                "--spring.flyway.user=" + FLYWAY_USERNAME,
                "--spring.flyway.password=" + FLYWAY_PASSWORD,
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.data.redis.password=" + REDIS_PASSWORD,
                "--basic-framework.web.cors-allowed-origins[0]=https://admin.boot-smoke.invalid",
                "--basic-framework.security.credential-encryption-key=" + CREDENTIAL_ENCRYPTION_KEY);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        processOutput.capture(process);
        return process;
    }

    private static void awaitHealthyResponse(Process process, ProcessOutput processOutput) throws InterruptedException {
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();

        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                fail(
                        "Packaged jar exited before becoming healthy (exit %d).%n%s",
                        process.exitValue(), processOutput.tail());
            }
            Integer port = processOutput.serverPort();
            if (port == null) {
                Thread.sleep(500);
                continue;
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"")) {
                    return;
                }
            } catch (IOException ignoredWhileStarting) {
                // 端口尚未监听属于启动期预期状态；超时后附带子进程日志诊断真实失败。
            }
            Thread.sleep(500);
        }
        fail("Packaged jar did not become healthy within %s.%n%s", STARTUP_TIMEOUT, processOutput.tail());
    }

    private static void assertFlywayMigratedEmptyDatabase() throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*), MAX(CAST(version AS UNSIGNED)) "
                        + "FROM flyway_schema_history WHERE success = TRUE")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(EXPECTED_MIGRATION_COUNT);
            assertThat(result.getInt(2)).isEqualTo(EXPECTED_LATEST_MIGRATION);
        }
    }

    private static void assertRedisAcceptsAuthenticatedCommands() throws Exception {
        org.testcontainers.containers.Container.ExecResult result =
                REDIS.execInContainer("redis-cli", "-a", REDIS_PASSWORD, "PING");
        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout().trim()).isEqualTo("PONG");
    }

    private static void stopApplication(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS))
                    .as("packaged jar process terminates after forced shutdown")
                    .isTrue();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static final class ProcessOutput {

        private static final int MAX_LOG_CHARACTERS = 40_000;
        private static final Pattern SERVER_PORT_PATTERN = Pattern.compile("Tomcat started on port ([0-9]+)");

        private final StringBuilder output = new StringBuilder();
        private Thread captureThread;
        private Integer serverPort;

        void capture(Process process) {
            captureThread = new Thread(() -> read(process), "packaged-jar-boot-smoke-output");
            captureThread.setDaemon(true);
            captureThread.start();
        }

        void awaitCompletion() throws InterruptedException {
            if (captureThread != null) {
                captureThread.join(5_000);
            }
        }

        synchronized String tail() {
            return output.toString();
        }

        synchronized Integer serverPort() {
            return serverPort;
        }

        private void read(Process process) {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    append(line);
                }
            } catch (IOException captureFailure) {
                append("Unable to capture child process output: "
                        + captureFailure.getClass().getSimpleName());
            }
        }

        private synchronized void append(String line) {
            output.append(line).append(System.lineSeparator());
            Matcher portMatcher = SERVER_PORT_PATTERN.matcher(line);
            if (portMatcher.find()) {
                serverPort = Integer.valueOf(portMatcher.group(1));
            }
            int excess = output.length() - MAX_LOG_CHARACTERS;
            if (excess > 0) {
                output.delete(0, excess);
            }
        }
    }
}
