package org.teamsparta.orderapi.integration;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.teamsparta.orderapi.domain.order.entity.OrderSagaState;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderSagaStateRepository;
import org.teamsparta.orderapi.domain.order.service.OrderSagaService;
import org.teamsparta.orderapi.domain.order.service.OrderTransactionalService;
import org.teamsparta.orderapi.global.enums.SagaState;
import org.teamsparta.orderapi.global.enums.Status;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Kafka 재시도·DLT 정책을 실제 브로커(Embedded)와 PostgreSQL에서 검증한다.
 * 정책: 시스템 예외는 1초 간격 3회 재시도 후 DLT, JSON 오류는 즉시 DLT, 비즈니스 실패는 정상 종료.
 */
@Tag("integration")
@SpringBootTest(properties = "scheduler.outbox.enabled=false")
@EmbeddedKafka(
        partitions = 1,
        topics = {
                "inventory-reserved-event", "inventory-reserved-event.DLT",
                "inventory-failed-event", "inventory-failed-event.DLT",
                "payment-succeeded-event", "payment-succeeded-event.DLT",
                "payment-failed-event", "payment-failed-event.DLT",
                "payment-request-event", "payment-request-event.DLT",
                "inventory-confirm-event", "inventory-confirm-event.DLT",
                "inventory-confirm-failed-event", "inventory-confirm-failed-event.DLT",
                "inventory-expired-event", "inventory-expired-event.DLT",
                "productSnapshot-reply-event", "productSnapshot-reply-event.DLT",
                "product-variant-event", "product-variant-event.DLT",
                "shipment-event", "shipment-event.DLT"
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class OrderKafkaErrorHandlingIntegrationTest {

    private static final String CONFIRM_TOPIC = "inventory-confirm-event";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("orderdb")
            .withUsername("root")
            .withPassword("rootpassword");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderSagaStateRepository orderSagaStateRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private KafkaTemplate<String, String> kafkaTemplate;
    @SpyBean
    private OrderSagaService orderSagaService;
    @SpyBean
    private OrderTransactionalService orderTransactionalService;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM idempotency_request");
        jdbcTemplate.update("DELETE FROM orders");
    }

    @Test
    @DisplayName("시스템 예외 → 최초 1회 + 재시도 3회 후 원본 레코드가 DLT로 간다 (재시도 간격 1초)")
    void systemFailure_retriesThreeTimesThenGoesToDlt() throws Exception {
        String payload = confirmedPayload(1L, UUID.randomUUID());
        doThrow(new IllegalStateException("db down")).when(orderSagaService).onInventoryConfirmed(any());

        try (Consumer<String, String> dlt = dltConsumer(CONFIRM_TOPIC)) {
            long startedAt = System.currentTimeMillis();
            kafkaTemplate.send(CONFIRM_TOPIC, "1", payload).get(5, TimeUnit.SECONDS);

            ConsumerRecord<String, String> record =
                    KafkaTestUtils.getSingleRecord(dlt, CONFIRM_TOPIC + ".DLT", Duration.ofSeconds(30));

            assertThat(record.value()).isEqualTo(payload);
            assertThat(record.key()).isEqualTo("1");
            assertThat(System.currentTimeMillis() - startedAt).isGreaterThanOrEqualTo(3000);
        }
        verify(orderSagaService, after(1500).times(4)).onInventoryConfirmed(any());
    }

    @Test
    @DisplayName("inventory-confirm-failed-event도 시스템 예외 시 재시도 후 DLT로 간다")
    void confirmFailedEvent_systemFailure_goesToDlt() throws Exception {
        String topic = "inventory-confirm-failed-event";
        String payload = """
                {"eventId":"%s","eventType":"inventory.confirm.failed","orderId":1,"sagaId":"%s","reservationId":"%s","reservationStatus":"EXPIRED"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        doThrow(new IllegalStateException("db down")).when(orderSagaService).onInventoryConfirmFailed(any());

        try (Consumer<String, String> dlt = dltConsumer(topic)) {
            kafkaTemplate.send(topic, "1", payload).get(5, TimeUnit.SECONDS);

            assertThat(KafkaTestUtils.getSingleRecord(dlt, topic + ".DLT", Duration.ofSeconds(30)).value())
                    .isEqualTo(payload);
        }
        verify(orderSagaService, times(4)).onInventoryConfirmFailed(any());
    }

    @Test
    @DisplayName("일시적 시스템 예외 → 재시도로 처리되어 DB에 한 번만 반영되고 DLT로 가지 않는다")
    void transientFailure_recoversByRetryAndKeepsDbConsistent() throws Exception {
        Orders order = savePaidOrder();
        doThrow(new IllegalStateException("db down")).doCallRealMethod()
                .when(orderSagaService).onInventoryConfirmed(any());

        try (Consumer<String, String> dlt = dltConsumer(CONFIRM_TOPIC)) {
            kafkaTemplate.send(CONFIRM_TOPIC, order.getId().toString(),
                    confirmedPayload(order.getId(), order.getSagaId())).get(5, TimeUnit.SECONDS);

            Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                            .isEqualTo(Status.COMPLETED));

            assertThat(KafkaTestUtils.getRecords(dlt, Duration.ofSeconds(2)).count()).isZero();
        }
        verify(orderSagaService, times(2)).onInventoryConfirmed(any());
        assertThat(orderSagaStateRepository.findById(order.getSagaId()).orElseThrow().getState())
                .isEqualTo(SagaState.COMPLETED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "inventory-reserved-event",
            "inventory-failed-event",
            "payment-succeeded-event",
            "payment-failed-event",
            "payment-request-event",
            "inventory-confirm-event",
            "inventory-confirm-failed-event",
            "inventory-expired-event",
            "productSnapshot-reply-event",
            "product-variant-event"
    })
    @DisplayName("JSON 파싱 오류 → 재시도 없이 즉시 DLT로 간다 (모든 리스너)")
    void invalidJson_goesToDltWithoutRetry(String topic) throws Exception {
        String badPayload = "{not-json:" + UUID.randomUUID();

        try (Consumer<String, String> dlt = dltConsumer(topic)) {
            long startedAt = System.currentTimeMillis();
            kafkaTemplate.send(topic, "k", badPayload).get(5, TimeUnit.SECONDS);

            ConsumerRecord<String, String> record =
                    KafkaTestUtils.getSingleRecord(dlt, topic + ".DLT", Duration.ofSeconds(15));

            assertThat(record.value()).isEqualTo(badPayload);
            // 재시도했다면 백오프 합계 3초 이상 걸린다
            assertThat(System.currentTimeMillis() - startedAt).isLessThan(3000);
        }
    }

    @Test
    @DisplayName("배송 Consumer는 자체적으로 JSON 오류를 기록하고 끝내므로 DLT로 가지 않는다 (기존 동작 유지)")
    void shipmentConsumer_invalidJson_isSwallowedByExistingCode() throws Exception {
        try (Consumer<String, String> dlt = dltConsumer("shipment-event")) {
            kafkaTemplate.send("shipment-event", "k", "{not-json").get(5, TimeUnit.SECONDS);

            assertThat(KafkaTestUtils.getRecords(dlt, Duration.ofSeconds(4)).count()).isZero();
        }
    }

    @Test
    @DisplayName("배송 Consumer의 처리 예외는 공통 정책대로 재시도 후 DLT로 간다")
    void shipmentConsumer_processingFailure_goesToDlt() throws Exception {
        // 존재하지 않는 주문 → applyShipmentStatus에서 NOT_FOUND_ORDER
        String payload = """
                {"eventId":"%s","shipmentId":1,"orderId":999999,"status":"SHIPPED","description":"d"}
                """.formatted(UUID.randomUUID());

        try (Consumer<String, String> dlt = dltConsumer("shipment-event")) {
            kafkaTemplate.send("shipment-event", "k", payload).get(5, TimeUnit.SECONDS);

            assertThat(KafkaTestUtils.getSingleRecord(dlt, "shipment-event.DLT", Duration.ofSeconds(30)).value())
                    .isEqualTo(payload);
        }
    }

    @Test
    @DisplayName("비즈니스 실패 → FAILED와 사유를 저장하고 재시도·DLT 없이 정상 종료한다")
    void businessFailure_isNotRetried() throws Exception {
        String topic = "productSnapshot-reply-event";
        String key = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO idempotency_request (idem_key, request_hash, status) VALUES (?, ?, 'PENDING')",
                key, "h".repeat(64));
        String payload = """
                {"requestId":"%s","success":false,"error":"MISSING_SKU=[SKU-X]","items":null,
                 "requestItem":[{"sku":"SKU-X","quantity":1}],"idemKey":"%s","userId":1}
                """.formatted(UUID.randomUUID(), key);

        try (Consumer<String, String> dlt = dltConsumer(topic)) {
            kafkaTemplate.send(topic, key, payload).get(5, TimeUnit.SECONDS);

            Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> "FAILED".equals(jdbcTemplate.queryForObject(
                    "SELECT status FROM idempotency_request WHERE idem_key = ?", String.class, key)));

            assertThat(KafkaTestUtils.getRecords(dlt, Duration.ofSeconds(2)).count()).isZero();
        }
        verify(orderTransactionalService, times(1)).failOrder(any(), any());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failure_reason FROM idempotency_request WHERE idem_key = ?", String.class, key))
                .isEqualTo("MISSING_SKU=[SKU-X]");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Long.class)).isZero();
    }

    @Test
    @DisplayName("멱등 기록 없는 응답은 시스템 오류로 재시도 후 DLT로 간다")
    void replyWithoutRecord_goesToDlt() throws Exception {
        String topic = "productSnapshot-reply-event";
        String payload = """
                {"requestId":"%s","success":false,"error":"MISSING_SKU=[SKU-X]","items":null,
                 "requestItem":[],"idemKey":"no-such-key","userId":1}
                """.formatted(UUID.randomUUID());

        try (Consumer<String, String> dlt = dltConsumer(topic)) {
            kafkaTemplate.send(topic, "k", payload).get(5, TimeUnit.SECONDS);

            assertThat(KafkaTestUtils.getSingleRecord(dlt, topic + ".DLT", Duration.ofSeconds(30)).value())
                    .isEqualTo(payload);
        }
        verify(orderTransactionalService, times(4)).failOrder(any(), any());
    }

    @Test
    @DisplayName("DLT 발행이 실패하면 오프셋을 커밋하지 않고 다시 처리하며, DLT가 복구되면 그때 넘어간다")
    void dltPublishFailure_doesNotLoseRecord() throws Exception {
        String topic = "inventory-expired-event";
        String payload = """
                {"eventId":"%s","eventType":"inventory.expired","orderId":1,"sagaId":"%s","reservationId":"%s"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        doThrow(new IllegalStateException("db down")).when(orderSagaService).onInventoryExpired(any());

        ArgumentMatcher<ProducerRecord<String, String>> toDlt = r -> r != null && (topic + ".DLT").equals(r.topic());
        CompletableFuture<SendResult<String, String>> failed =
                CompletableFuture.failedFuture(new IllegalStateException("dlt broker down"));
        doReturn(failed).when(kafkaTemplate).send(argThat(toDlt));

        long offset = kafkaTemplate.send(topic, "1", payload).get(5, TimeUnit.SECONDS).getRecordMetadata().offset();

        // 재시도 소진 → DLT 실패 → 같은 레코드를 처음부터 다시 처리 (최초 1 + 재시도 3 = 4회를 넘어간다)
        verify(orderSagaService, timeout(30_000).atLeast(5)).onInventoryExpired(any());
        verify(kafkaTemplate, timeout(30_000).atLeast(1)).send(argThat(toDlt));
        assertThat(committedOffset(topic))
                .as("DLT 실패 중에는 이 레코드 뒤로 오프셋이 커밋되면 안 된다")
                .isLessThanOrEqualTo(offset);

        // DLT 복구 → 레코드가 DLT로 가고 오프셋이 넘어간다
        try (Consumer<String, String> dlt = dltConsumer(topic)) {
            doCallRealMethod().when(kafkaTemplate).send(argThat(toDlt));

            assertThat(KafkaTestUtils.getSingleRecord(dlt, topic + ".DLT", Duration.ofSeconds(30)).value())
                    .isEqualTo(payload);
        }
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(committedOffset(topic)).isEqualTo(offset + 1));
        reset(orderSagaService);
        verify(orderSagaService, after(1500).never()).onInventoryExpired(any());
    }

    private long committedOffset(String topic) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString()))) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets("order-api")
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS)
                    .get(new TopicPartition(topic, 0));
            return committed == null ? 0 : committed.offset();
        }
    }

    private Consumer<String, String> dltConsumer(String topic) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("dlt-" + UUID.randomUUID(), "false", embeddedKafka);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        // 앞선 테스트의 레코드를 읽지 않도록 지금의 끝 위치에서 시작한다 (seekToEnd는 lazy라 position으로 확정)
        embeddedKafka.consumeFromEmbeddedTopics(consumer, true, topic + ".DLT");
        consumer.assignment().forEach(consumer::position);
        return consumer;
    }

    private Orders savePaidOrder() {
        Orders order = Orders.createNew("O-" + UUID.randomUUID().toString().substring(0, 8), 1L);
        order.updateStatus(Status.PAID);
        Orders saved = orderRepository.save(order);
        OrderSagaState saga = OrderSagaState.start(saved.getSagaId(), saved.getId());
        saga.updateState(SagaState.PAYMENT_COMPLETED, null, UUID.randomUUID());
        orderSagaStateRepository.save(saga);
        return saved;
    }

    private String confirmedPayload(Long orderId, UUID sagaId) {
        return """
                {"eventId":"%s","eventType":"inventory.confirmed","orderId":%d,"sagaId":"%s"}
                """.formatted(UUID.randomUUID(), orderId, sagaId);
    }
}
