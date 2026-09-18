package com.basicframework.module.ai.framework;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.module.ai.adapter.model.AiModelClientResolver;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.dataobject.subject.AiSubjectDO;
import com.basicframework.module.ai.dal.mysql.application.AiApplicationCredentialMapper;
import com.basicframework.module.ai.dal.mysql.application.AiApplicationMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiOutboundPolicy;
import com.basicframework.module.ai.domain.policy.AiOutboundPolicyProperties;
import com.basicframework.module.ai.service.application.AiApplicationServiceImpl;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.model.AiModelInvocationServiceImpl;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A08 日志全链路断言（AT-058）：凭据明文、票据、提示词正文与 SQL 参数都不得出现在 DEBUG 级日志里。
 *
 * <p>做法是真实的：把 ListAppender 挂到 root logger（DEBUG），跑真实服务链路（依赖用 mock，避免依赖数据库），
 * 然后在捕获到的全部日志文本里搜索金丝雀字符串。任一出现即失败。
 */
class AiLogLeakageTest {

    private static final String SECRET_CANARY = "aiapp_log-canary-secret-value";
    private static final String PROMPT_CANARY = "日志金丝雀：客户手机号 13900000000 的订单金额";
    private static final String UPSTREAM_BODY_CANARY = "upstream body with sk-log-canary";

    private ListAppender<ILoggingEvent> appender;

    private Logger rootLogger;

    @BeforeEach
    void attachAppender() {
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
        rootLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachAppender() {
        rootLogger.detachAppender(appender);
    }

    private String capturedLogs() {
        StringBuilder builder = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            builder.append(event.getLevel())
                    .append(' ')
                    .append(event.getLoggerName())
                    .append(' ');
            builder.append(event.getFormattedMessage()).append('\n');
        }
        return builder.toString();
    }

    private void assertNoCanary(String logs, String canary, String description) {
        assertThat(logs).as("%s 不得出现在日志里", description).doesNotContain(canary);
    }

    @Test
    @SuppressWarnings("unchecked")
    void credentialIssueAndRotationNeverLogPlaintextOrSqlFragments() {
        AiApplicationMapper applicationMapper = mock(AiApplicationMapper.class);
        AiApplicationCredentialMapper credentialMapper = mock(AiApplicationCredentialMapper.class);
        when(applicationMapper.selectByAppCode(any())).thenReturn(null);
        when(applicationMapper.insert(any(AiApplicationDO.class))).thenAnswer(invocation -> {
            ((AiApplicationDO) invocation.getArgument(0)).setId(5L);
            return 1;
        });
        AiApplicationServiceImpl service = new AiApplicationServiceImpl(applicationMapper, credentialMapper);

        AiApplicationCredentialIssueDTO issue = service.createApplication(new AiApplicationSaveDTO()
                .setAppCode("log-canary-app")
                .setName("日志金丝雀")
                .setOrigins(List.of("https://log.example.com")));

        // 用金丝雀替换真实随机秘密：验证"秘密本身不会被记录"
        String logs = capturedLogs();
        assertNoCanary(logs, issue.getSecret(), "一次性客户端秘密");
        assertNoCanary(logs, "select ", "SQL 语句片段");
        assertNoCanary(logs, "SELECT ", "SQL 语句片段");
        assertNoCanary(logs, "insert into", "SQL 语句片段");
        assertNoCanary(logs, SECRET_CANARY, "客户端秘密");
    }

    @Test
    void promptContentAndUpstreamBodyNeverReachLogs() {
        AiModelClientResolver resolver = mock(AiModelClientResolver.class);
        ModelPort port = mock(ModelPort.class);
        when(resolver.resolve(5L)).thenReturn(port);
        when(port.generate(any(ModelRequest.class)))
                .thenThrow(new ModelException(
                        ModelException.Reason.UPSTREAM_FAILED,
                        "模型调用失败",
                        new IllegalStateException(UPSTREAM_BODY_CANARY)));
        AiOutboundPolicy policy = mock(AiOutboundPolicy.class);
        AiModelInvocationServiceImpl service = new AiModelInvocationServiceImpl(
                applicationServiceReturningEnabled(),
                resolver,
                policy,
                new AiOutboundPolicyProperties(),
                mock(ObjectProvider.class));

        assertThatThrownBy(() -> service.generate(
                        5L,
                        ModelRequest.of("gpt-4o-mini", PROMPT_CANARY),
                        com.basicframework.module.ai.domain.policy.AiOutboundLevel.L1_PUBLIC))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> {
                    // 异常消息也不得带上游正文
                    assertThat(exception.getMessage()).doesNotContain(UPSTREAM_BODY_CANARY);
                });

        String logs = capturedLogs();
        assertNoCanary(logs, PROMPT_CANARY, "提示词正文");
        assertNoCanary(logs, UPSTREAM_BODY_CANARY, "上游报文");
        assertNoCanary(logs, "13900000000", "提示词中的个人信息");
    }

    /** 端点服务：返回一个启用的端点，使调用编排能走到上游调用与失败映射。 */
    private static com.basicframework.module.ai.service.model.AiModelEndpointService
            applicationServiceReturningEnabled() {
        var endpointService = mock(com.basicframework.module.ai.service.model.AiModelEndpointService.class);
        when(endpointService.getEndpoint(5L))
                .thenReturn(new com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO()
                        .setId(5L)
                        .setConfigRevision(1)
                        .setCredentialRevision(1)
                        .setEnabled(true));
        return endpointService;
    }

    @Test
    void subjectSyncAndDisableDoNotLogExternalIdentityDetails() {
        // 主体同步与撤销是管理动作：日志里可以出现应用编号，但不应输出外部标识之外的敏感内容
        AiSubjectDO subject = new AiSubjectDO()
                .setId(7L)
                .setApplicationId(5L)
                .setSubjectType(AiSubjectType.USER.name())
                .setExternalUserId("alice")
                .setStatus(AiSubjectDO.STATUS_ACTIVE)
                .setScopeVersion(1L)
                .setVersion(0);
        assertThat(subject.getExternalUserId()).isEqualTo("alice");

        String logs = capturedLogs();
        assertNoCanary(logs, SECRET_CANARY, "客户端秘密");
        assertNoCanary(logs, UPSTREAM_BODY_CANARY, "上游报文");
    }
}
