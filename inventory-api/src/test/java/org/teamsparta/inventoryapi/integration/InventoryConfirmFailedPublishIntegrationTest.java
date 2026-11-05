package org.teamsparta.inventoryapi.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservation;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryStock;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.OrderConfirmResult;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.OrderCreateResult;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryReservationRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryStockRepository;
import org.teamsparta.inventoryapi.domain.inventory.scheduler.OutboxPublisherJob;
import org.teamsparta.inventoryapi.domain.inventory.service.InventoryExpireService;
import org.teamsparta.inventoryapi.domain.inventory.service.InventoryService;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 OutboxPublisherJob이 확정 실패 이벤트를 올바른 토픽으로 발행하는지 EmbeddedKafka로 검증한다.
 * 페이로드 필드는 order-api의 InventoryConfirmFailureIntegrationTest가 수신하는 계약과 같다.
 */
@EmbeddedKafka(
        partitions = 1,
        topics = {"inventory-confirm-failed-event"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class InventoryConfirmFailedPublishIntegrationTest extends InventoryIntegrationTestSupport {

    private static final String TOPIC = "inventory-confirm-failed-event";
    private static final long ORDER_ID = 4242L;

    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private InventoryExpireService inventoryExpireService;
    @Autowired
    private InventoryStockRepository inventoryStockRepository;
    @Autowired
    private InventoryReservationRepository inventoryReservationRepository;
    @Autowired
    private OutboxPublisherJob outboxPublisherJob;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM inventory_reservation");
        jdbcTemplate.update("DELETE FROM inventory_stock");
    }

    @Test
    @DisplayName("Publisher가 확정 실패 이벤트를 전용 토픽으로 주문 ID 키와 함께 발행하고 SENT로 바꾼다")
    void publish_confirmFailedEvent_toDedicatedTopic() throws Exception {
        String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8);
        inventoryStockRepository.save(InventoryStock.create(sku, 10));
        inventoryService.reserveInventory(new OrderCreateResult(
                UUID.randomUUID(), "order.created", ORDER_ID, UUID.randomUUID(), 1L,
                List.of(new OrderCreateResult.Item(sku, 4))));
        InventoryReservation reservation = inventoryReservationRepository.findByOrderId(ORDER_ID).orElseThrow();
        inventoryExpireService.expireOne(reservation.getId());
        inventoryService.onInventoryConfirmed(new OrderConfirmResult(
                UUID.randomUUID(), "order.confirm", ORDER_ID, reservation.getSagaId(), reservation.getId()));

        try (Consumer<String, String> consumer = createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, TOPIC);

            outboxPublisherJob.publish();

            ConsumerRecord<String, String> record =
                    KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(10));
            assertThat(record.key()).isEqualTo(String.valueOf(ORDER_ID));
            JsonNode payload = objectMapper.readTree(record.value());
            assertThat(payload.get("orderId").asLong()).isEqualTo(ORDER_ID);
            assertThat(payload.get("sagaId").asText()).isEqualTo(reservation.getSagaId().toString());
            assertThat(payload.get("reservationId").asText()).isEqualTo(reservation.getId().toString());
            assertThat(payload.get("reservationStatus").asText()).isEqualTo("EXPIRED");
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_event WHERE event_type = ?", String.class, TOPIC)).isEqualTo("SENT");
    }

    private Consumer<String, String> createConsumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("confirm-failed-test", "false", embeddedKafka);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }
}
