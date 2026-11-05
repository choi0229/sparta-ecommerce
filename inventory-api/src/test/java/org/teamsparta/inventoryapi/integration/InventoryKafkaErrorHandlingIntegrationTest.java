package org.teamsparta.inventoryapi.integration;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryStock;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryStockRepository;
import org.teamsparta.inventoryapi.domain.inventory.service.InventoryService;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * inventory-api의 Kafka 재시도·DLT 정책을 실제 브로커(Embedded)와 PostgreSQL에서 검증한다.
 */
@EmbeddedKafka(
        partitions = 1,
        topics = {
                "order-create-event", "order-create-event.DLT",
                "order-confirm-event", "order-confirm-event.DLT",
                "inventory-init-event", "inventory-init-event.DLT"
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class InventoryKafkaErrorHandlingIntegrationTest extends InventoryIntegrationTestSupport {

    @DynamicPropertySource
    static void startListeners(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
    }

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private InventoryStockRepository inventoryStockRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private InventoryService inventoryService;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM inventory_reservation");
        jdbcTemplate.update("DELETE FROM inventory_stock");
    }

    @Test
    @DisplayName("시스템 예외 → 최초 1회 + 재시도 3회 후 원본 레코드가 DLT로 간다 (재시도 간격 1초)")
    void systemFailure_retriesThreeTimesThenGoesToDlt() throws Exception {
        String payload = orderCreated(1L, "SKU-X", 1);
        doThrow(new IllegalStateException("db down")).when(inventoryService).reserveInventory(any());

        try (Consumer<String, String> dlt = dltConsumer("order-create-event")) {
            long startedAt = System.currentTimeMillis();
            kafkaTemplate.send("order-create-event", "1", payload).get(5, TimeUnit.SECONDS);

            assertThat(KafkaTestUtils.getSingleRecord(dlt, "order-create-event.DLT", Duration.ofSeconds(30)).value())
                    .isEqualTo(payload);
            assertThat(System.currentTimeMillis() - startedAt).isGreaterThanOrEqualTo(3000);
        }
        verify(inventoryService, after(1500).times(4)).reserveInventory(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"order-create-event", "order-confirm-event", "inventory-init-event"})
    @DisplayName("JSON 파싱 오류 → 재시도 없이 즉시 DLT로 간다 (모든 리스너)")
    void invalidJson_goesToDltWithoutRetry(String topic) throws Exception {
        String badPayload = "{not-json:" + UUID.randomUUID();

        try (Consumer<String, String> dlt = dltConsumer(topic)) {
            long startedAt = System.currentTimeMillis();
            kafkaTemplate.send(topic, "k", badPayload).get(5, TimeUnit.SECONDS);

            assertThat(KafkaTestUtils.getSingleRecord(dlt, topic + ".DLT", Duration.ofSeconds(15)).value())
                    .isEqualTo(badPayload);
            assertThat(System.currentTimeMillis() - startedAt).isLessThan(3000);
        }
    }

    @Test
    @DisplayName("재고 부족은 비즈니스 실패 → 재시도·DLT 없이 실패 이벤트를 저장하고 재고를 바꾸지 않는다")
    void outOfStock_isNotRetried() throws Exception {
        String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8);
        inventoryStockRepository.save(InventoryStock.create(sku, 1));

        try (Consumer<String, String> dlt = dltConsumer("order-create-event")) {
            kafkaTemplate.send("order-create-event", "2", orderCreated(2L, sku, 5)).get(5, TimeUnit.SECONDS);

            Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM outbox_event WHERE event_type = 'inventory-failed-event'", Long.class) == 1);

            assertThat(KafkaTestUtils.getRecords(dlt, Duration.ofSeconds(2)).count()).isZero();
        }
        verify(inventoryService, times(1)).reserveInventory(any());
        InventoryStock stock = inventoryStockRepository.findById(sku).orElseThrow();
        assertThat(stock.getTotalQuantity()).isEqualTo(1);
        assertThat(stock.getReservedQuantity()).isZero();
    }

    private String orderCreated(long orderId, String sku, int quantity) {
        return """
                {"eventId":"%s","eventType":"order.created","orderId":%d,"sagaId":"%s","userId":1,
                 "items":[{"sku":"%s","quantity":%d}]}
                """.formatted(UUID.randomUUID(), orderId, UUID.randomUUID(), sku, quantity);
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
}
