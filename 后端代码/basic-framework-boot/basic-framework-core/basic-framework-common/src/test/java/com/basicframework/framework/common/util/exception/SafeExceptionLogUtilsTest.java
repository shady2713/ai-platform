package com.basicframework.framework.common.util.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafeExceptionLogUtilsTest {

    @Test
    void format_keepsTypesAndFramesButRemovesEveryExceptionMessage() {
        IllegalArgumentException cause = new IllegalArgumentException("private-cause-value");
        RuntimeException failure = new RuntimeException("private-top-level-value", cause);
        failure.setStackTrace(
                new StackTraceElement[] {new StackTraceElement("com.example.Service", "execute", "Service.java", 42)});

        String formatted = SafeExceptionLogUtils.format(failure);

        assertThat(formatted)
                .contains(RuntimeException.class.getName())
                .contains(IllegalArgumentException.class.getName())
                .contains("com.example.Service.execute(Service.java:42)")
                .doesNotContain("private-top-level-value", "private-cause-value");
    }

    @Test
    void format_isBounded() {
        RuntimeException first = new RuntimeException("first-secret");
        StackTraceElement[] frames = new StackTraceElement[1000];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = new StackTraceElement(
                    "com.example.component.WithAnIntentionallyLongClassName" + index,
                    "executeOperation",
                    "WithAnIntentionallyLongClassName.java",
                    index + 1);
        }
        first.setStackTrace(frames);

        String formatted = SafeExceptionLogUtils.format(first);

        assertThat(formatted)
                .contains("... truncated")
                .doesNotContain("first-secret")
                .hasSizeLessThan(32_100);
        assertThat(SafeExceptionLogUtils.format(null)).isEqualTo("null");
    }

    @Test
    void format_limitsCauseDepthAndStopsAtCycles() {
        RuntimeException first = new RuntimeException("first-secret");
        first.setStackTrace(new StackTraceElement[0]);
        RuntimeException current = first;
        for (int index = 1; index < 10; index++) {
            RuntimeException next = new RuntimeException("secret-" + index);
            next.setStackTrace(new StackTraceElement[0]);
            current.initCause(next);
            current = next;
        }

        String depthLimited = SafeExceptionLogUtils.format(first);

        assertThat(depthLimited
                        .lines()
                        .filter(line -> line.contains(RuntimeException.class.getName()))
                        .count())
                .isEqualTo(8);
        RuntimeException cycleStart = new RuntimeException("cycle-start-secret");
        RuntimeException cycleEnd = new RuntimeException("cycle-end-secret");
        cycleStart.setStackTrace(new StackTraceElement[0]);
        cycleEnd.setStackTrace(new StackTraceElement[0]);
        cycleStart.initCause(cycleEnd);
        cycleEnd.initCause(cycleStart);
        String cycleSafe = SafeExceptionLogUtils.format(cycleStart);
        assertThat(cycleSafe
                        .lines()
                        .filter(line -> line.contains(RuntimeException.class.getName()))
                        .count())
                .isEqualTo(2);
        assertThat(cycleSafe).doesNotContain("cycle-start-secret", "cycle-end-secret");
    }
}
