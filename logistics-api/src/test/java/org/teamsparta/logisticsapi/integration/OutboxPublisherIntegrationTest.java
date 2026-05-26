package org.teamsparta.logisticsapi.integration;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.teamsparta.logisticsapi.domain.logistics.entity.OutboxEvent;
import org.teamsparta.logisticsapi.domain.logistics.repository.OutboxEventRepository;
import org.teamsparta.logisticsapi.domain.logistics.scheduler.OutboxPublisherJob;
import org.teamsparta.logisticsapi.global.enums.OutboxStatus;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(properties = {
        "scheduler.outbox.enabled=false",
        "scheduler.stale-recovery.enabled=false"
})
@EmbeddedKafka(
        partitions = 1,
        topics = {"shipment-event"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers
class OutboxPublisherIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("logisticsdb")
            .withUsername("root")
            .withPassword("rootpassword");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private OutboxPublisherJob outboxPublisherJob;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @AfterEach
    void cleanup() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("PENDING 상태의 outbox_event가 Kafka에 발행되고 SENT로 전환된다")
    void pendingOutboxEvent_publishedToKafkaAndBecomeSent() {
        // given
        OutboxEvent event = OutboxEvent.pending(
                "shipment", "42", "shipment-created-event", "{\"orderId\":42}");
        OutboxEvent saved = outboxEventRepository.save(event);
        Long savedId = saved.getId();

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "it-group", "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new StringDeserializer()
        ).createConsumer()) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, "shipment-event");

            // when
            outboxPublisherJob.publish();

            // then — DB: PENDING → SENT, retryCount 변화 없음
            OutboxEvent result = outboxEventRepository.findById(savedId).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(result.getRetryCount()).isEqualTo(0);
            assertThat(result.getSentAt()).isNotNull();

            // then — Kafka: shipment-event 토픽에 이벤트 발행 확인
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(
                    consumer, Duration.ofSeconds(5));
            assertThat(records.count()).isGreaterThanOrEqualTo(1);
            ConsumerRecord<String, String> record = records.iterator().next();
            assertThat(record.topic()).isEqualTo("shipment-event");
            assertThat(record.key()).isEqualTo("42");
        }
    }
}
