package org.teamsparta.orderapi.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxEventClaimer;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxPublisherJob;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxPublisherScheduler;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxStatusUpdater;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Outbox Publisher의 배치 크기와 실행 주기 설정이 실제 스케줄링에 적용되는지 확인한다.
 * DB와 Kafka 없이 Publisher 빈과 스케줄링만 올리고, 스케줄러가 호출한 조회의 한도를 본다.
 */
class OutboxPublisherSettingsTest {

    @Configuration
    @EnableScheduling
    static class SchedulingConfig {
    }

    private final OutboxEventClaimer outboxEventClaimer = mock(OutboxEventClaimer.class);

    @SuppressWarnings("unchecked")
    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfig.class)
                .withBean(OutboxEventClaimer.class, () -> outboxEventClaimer)
                .withBean(OutboxStatusUpdater.class, () -> mock(OutboxStatusUpdater.class))
                .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(OutboxPublisherJob.class)
                .withBean(OutboxPublisherScheduler.class);
    }

    @Test
    @DisplayName("설정한 주기로 스케줄되고, 설정한 배치 크기로 claim과 stale 복구를 한다")
    void configuredValues_areApplied() {
        contextRunner()
                .withPropertyValues("scheduler.outbox.batch-size=7", "scheduler.outbox.fixed-delay-ms=123")
                .run(context -> {
                    assertThat(publishInterval(context)).isEqualTo(Duration.ofMillis(123));
                    verify(outboxEventClaimer, timeout(3000).atLeastOnce()).claimBatch(any(), eq(7));
                    verify(outboxEventClaimer, atLeastOnce()).recoverStaleProcessing(any(), eq(7));
                });
    }

    @Test
    @DisplayName("설정이 없으면 기존 값(500ms, 50건)으로 동작한다")
    void defaults_keepPreviousBehavior() {
        contextRunner().run(context -> {
            assertThat(publishInterval(context)).isEqualTo(Duration.ofMillis(500));
            verify(outboxEventClaimer, timeout(3000).atLeastOnce()).claimBatch(any(), eq(50));
            verify(outboxEventClaimer, atLeastOnce()).recoverStaleProcessing(any(), eq(50));
        });
    }

    @Test
    @DisplayName("배치 크기가 1보다 작으면 기동하지 않는다")
    void invalidBatchSize_failsStartup() {
        contextRunner()
                .withPropertyValues("scheduler.outbox.batch-size=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("scheduler.outbox.batch-size"));
    }

    @Test
    @DisplayName("stale 복구 기준 시간이 1보다 작으면 기동하지 않는다")
    void invalidStaleTimeout_failsStartup() {
        contextRunner()
                .withPropertyValues("scheduler.outbox.stale-processing-timeout-ms=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("scheduler.outbox.stale-processing-timeout-ms"));
    }

    private static Duration publishInterval(AssertableApplicationContext context) {
        List<FixedDelayTask> tasks = context.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(FixedDelayTask.class::isInstance)
                .map(FixedDelayTask.class::cast)
                .toList();
        assertThat(tasks).hasSize(1);
        return tasks.get(0).getIntervalDuration();
    }
}
