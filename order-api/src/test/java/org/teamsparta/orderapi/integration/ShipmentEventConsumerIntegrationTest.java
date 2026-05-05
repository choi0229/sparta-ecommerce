package org.teamsparta.orderapi.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.event.dto.ShipmentEventPayload;
import org.teamsparta.orderapi.domain.order.repository.IdempotencyRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.global.enums.IdempotencyStatus;
import org.teamsparta.orderapi.global.enums.ShipmentStatus;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(properties = {
        "scheduler.outbox.enabled=false"
})
@EmbeddedKafka(
        partitions = 1,
        topics = {"shipment-event"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers
class ShipmentEventConsumerIntegrationTest {

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
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        idempotencyRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("shipment-event를 consume하면 orders.shipment_status가 업데이트되고 idempotency record가 COMPLETED로 생성된다")
    void shipmentEvent_updatesShipmentStatusAndCreatesIdempotencyRecord() throws Exception {
        // given
        Orders order = Orders.createNew("ORDER-IT-001", 1L);
        Orders saved = orderRepository.save(order);
        String eventId = "evt-it-001";

        ShipmentEventPayload payload = new ShipmentEventPayload(
                eventId, 1L, saved.getId(), "SHIPPED", "발송 완료");

        // when
        kafkaTemplate.send("shipment-event", objectMapper.writeValueAsString(payload))
                .get(2, TimeUnit.SECONDS);

        // then — orders.shipment_status = SHIPPED 반영 확인
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Orders result = orderRepository.findById(saved.getId()).orElseThrow();
            assertThat(result.getShipmentStatus()).isEqualTo(ShipmentStatus.SHIPPED);
        });

        // then — idempotency record가 COMPLETED로 생성됨
        String idemKey = "shipment-event:" + eventId;
        IdempotencyRecord record = idempotencyRepository.findById(idemKey).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.getOrderId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("동일 eventId로 다른 상태의 이벤트를 재전송해도 shipment_status가 변경되지 않는다")
    void duplicateEventId_doesNotOverrideShipmentStatus() throws Exception {
        // given — SHIPPED 이벤트 처리 완료
        Orders order = Orders.createNew("ORDER-IT-002", 1L);
        Orders saved = orderRepository.save(order);
        String eventId = "evt-it-002";

        ShipmentEventPayload firstPayload = new ShipmentEventPayload(
                eventId, 1L, saved.getId(), "SHIPPED", "발송 완료");
        kafkaTemplate.send("shipment-event", objectMapper.writeValueAsString(firstPayload))
                .get(2, TimeUnit.SECONDS);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Orders result = orderRepository.findById(saved.getId()).orElseThrow();
            assertThat(result.getShipmentStatus()).isEqualTo(ShipmentStatus.SHIPPED);
        });

        // when — 동일 eventId로 다른 status(DELIVERED) 재전송
        ShipmentEventPayload duplicatePayload = new ShipmentEventPayload(
                eventId, 1L, saved.getId(), "DELIVERED", "중복 이벤트");
        kafkaTemplate.send("shipment-event", objectMapper.writeValueAsString(duplicatePayload))
                .get(2, TimeUnit.SECONDS);

        // then — shipment_status가 SHIPPED 유지 (DELIVERED로 변경되지 않음)
        // during: 300ms 동안 조건이 계속 참이어야 통과 → 중복 처리 시 DELIVERED로 변경됐다면 실패
        Awaitility.await()
                .during(300, TimeUnit.MILLISECONDS)
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Orders result = orderRepository.findById(saved.getId()).orElseThrow();
                    assertThat(result.getShipmentStatus()).isEqualTo(ShipmentStatus.SHIPPED);
                });

        // then — idempotency record는 COMPLETED 상태 유지 (새로 생성·변경 없음)
        String idemKey = "shipment-event:" + eventId;
        IdempotencyRecord record = idempotencyRepository.findById(idemKey).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.getOrderId()).isEqualTo(saved.getId());
    }
}
