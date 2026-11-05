package org.teamsparta.orderapi.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.teamsparta.orderapi.domain.order.entity.OrderSagaState;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryReservedResult;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderSagaStateRepository;
import org.teamsparta.orderapi.domain.order.service.OrderSagaService;
import org.teamsparta.orderapi.domain.payment.event.PaymentEventConsumer;
import org.teamsparta.orderapi.domain.payment.event.PaymentSucceededEvent;
import org.teamsparta.orderapi.domain.payment.service.MockPaymentJudge;
import org.teamsparta.orderapi.global.enums.SagaState;
import org.teamsparta.orderapi.global.enums.Status;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Mock 결제 판정이 주문당 한 번만 이루어지고, 판정 기록과 결과 Outbox가 함께 저장되는지 실제 PostgreSQL에서 검증한다.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "scheduler.outbox.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@Testcontainers
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PaymentMockResultIntegrationTest {

    private static final long ORDER_ID = 501L;

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
    private PaymentEventConsumer paymentEventConsumer;
    @Autowired
    private OrderSagaService orderSagaService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderSagaStateRepository orderSagaStateRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private ObjectMapper objectMapper;
    @MockBean
    private MockPaymentJudge mockPaymentJudge;
    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;
    @MockBean
    private KafkaAdmin kafkaAdmin;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM payment_mock_result");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM orders");
    }

    @Test
    @DisplayName("승인 → 판정 기록(금액, 성공)과 결제 성공 Outbox 저장")
    void approved_savesResultAndSucceededEvent() throws Exception {
        given(mockPaymentJudge.approve()).willReturn(true);

        paymentEventConsumer.onPaymentRequested(paymentRequest(ORDER_ID, "2500.00"));

        Map<String, Object> result = jdbcTemplate.queryForMap("SELECT * FROM payment_mock_result");
        assertThat(result.get("order_id")).isEqualTo(ORDER_ID);
        assertThat((BigDecimal) result.get("amount")).isEqualByComparingTo("2500");
        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("failure_reason")).isNull();
        assertThat(result.get("processed_at")).isNotNull();
        assertThat(outboxCount("payment-succeeded-event")).isEqualTo(1);
        assertThat(outboxCount("payment-failed-event")).isZero();
    }

    @Test
    @DisplayName("거절 → 판정 기록(실패, 사유)과 결제 실패 Outbox 저장")
    void declined_savesResultAndFailedEvent() throws Exception {
        given(mockPaymentJudge.approve()).willReturn(false);

        paymentEventConsumer.onPaymentRequested(paymentRequest(ORDER_ID, "2500.00"));

        Map<String, Object> result = jdbcTemplate.queryForMap("SELECT * FROM payment_mock_result");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("failure_reason")).isEqualTo("DECLINED");
        assertThat(outboxCount("payment-failed-event")).isEqualTo(1);
        assertThat(outboxCount("payment-succeeded-event")).isZero();
    }

    @Test
    @DisplayName("같은 결제 이벤트 재처리 → 다시 판정하지 않고, 결과와 Outbox가 그대로다")
    void reprocessing_doesNotJudgeAgain() throws Exception {
        given(mockPaymentJudge.approve()).willReturn(false, true);

        paymentEventConsumer.onPaymentRequested(paymentRequest(ORDER_ID, "2500.00"));
        paymentEventConsumer.onPaymentRequested(paymentRequest(ORDER_ID, "9999.00"));

        verify(mockPaymentJudge, times(1)).approve();
        Map<String, Object> result = jdbcTemplate.queryForMap("SELECT * FROM payment_mock_result");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat((BigDecimal) result.get("amount")).isEqualByComparingTo("2500");
        assertThat(outboxCount("payment-failed-event")).isEqualTo(1);
        assertThat(outboxCount("payment-succeeded-event")).isZero();
    }

    @RepeatedTest(20)
    @DisplayName("동시 중복 처리 → 판정 기록 1건, 후속 Outbox 1건이고 둘이 일치한다")
    void concurrentProcessing_oneResultAndOneOutbox() throws Exception {
        // 동시 진입한 쪽들의 판정이 달라도 먼저 저장한 결과 하나만 남아야 한다
        given(mockPaymentJudge.approve()).willReturn(true, false);
        String message = paymentRequest(ORDER_ID, "2500.00");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> process(start, message));
            Future<?> second = executor.submit(() -> process(start, message));
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payment_mock_result", Long.class)).isEqualTo(1);
        boolean success = jdbcTemplate.queryForObject("SELECT success FROM payment_mock_result", Boolean.class);
        assertThat(outboxCount("payment-succeeded-event")).isEqualTo(success ? 1 : 0);
        assertThat(outboxCount("payment-failed-event")).isEqualTo(success ? 0 : 1);
        cleanUp();
    }

    @Test
    @DisplayName("결과 이벤트 직렬화 실패(체크 예외) → 판정 기록도 롤백되어 재시도 시 다시 처리할 수 있다")
    void serializationFailure_rollsBackResult() throws Exception {
        given(mockPaymentJudge.approve()).willReturn(true);
        String message = paymentRequest(ORDER_ID, "2500.00");
        doThrow(new JsonProcessingException("serialization failed") { })
                .when(objectMapper).writeValueAsString(any(PaymentSucceededEvent.class));

        assertThatThrownBy(() -> paymentEventConsumer.onPaymentRequested(message))
                .isInstanceOf(JsonProcessingException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payment_mock_result", Long.class)).isZero();

        reset(objectMapper);
        paymentEventConsumer.onPaymentRequested(message);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payment_mock_result", Long.class)).isEqualTo(1);
        assertThat(outboxCount("payment-succeeded-event")).isEqualTo(1);
    }

    @Test
    @DisplayName("Outbox 저장 실패 → 판정 기록도 롤백된다")
    void outboxFailure_rollsBackResult() throws Exception {
        given(mockPaymentJudge.approve()).willReturn(true);
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_outbox() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'outbox insert failed'; END; $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_outbox_insert BEFORE INSERT ON outbox_event
                FOR EACH ROW EXECUTE FUNCTION fail_outbox()
                """);
        try {
            assertThatThrownBy(() -> paymentEventConsumer.onPaymentRequested(paymentRequest(ORDER_ID, "2500.00")))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER fail_outbox_insert ON outbox_event");
            jdbcTemplate.execute("DROP FUNCTION fail_outbox()");
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payment_mock_result", Long.class)).isZero();
    }

    @Test
    @DisplayName("주문의 결제 금액이 결제 요청을 거쳐 판정 기록의 amount와 일치한다")
    void storedAmount_matchesOrderPayAmount() throws Exception {
        given(mockPaymentJudge.approve()).willReturn(true);
        Orders order = Orders.createNew("O-" + UUID.randomUUID().toString().substring(0, 8), 1L);
        order.applyTotalAmount(new BigDecimal("3700"));
        Orders saved = orderRepository.save(order);
        OrderSagaState saga = OrderSagaState.start(saved.getSagaId(), saved.getId());
        saga.updateState(SagaState.INVENTORY_RESERVE_REQUESTED, null, null);
        orderSagaStateRepository.save(saga);

        orderSagaService.onInventoryReserved(new InventoryReservedResult(UUID.randomUUID(), "inventory.reserved",
                saved.getSagaId(), saved.getId(), UUID.randomUUID(), null, null));
        String paymentRequest = jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_event WHERE event_type = 'payment-request-event'", String.class);
        assertThat(orderRepository.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(Status.RESERVED);

        paymentEventConsumer.onPaymentRequested(paymentRequest);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount FROM payment_mock_result WHERE order_id = ?", BigDecimal.class, saved.getId()))
                .isEqualByComparingTo("3700");
    }

    private void process(CountDownLatch start, String message) {
        try {
            start.await();
            paymentEventConsumer.onPaymentRequested(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private long outboxCount(String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?", Long.class, eventType);
    }

    private String paymentRequest(long orderId, String amount) {
        return """
                {"eventId":"%s","eventType":"payment.requested","orderId":%d,"sagaId":"%s","userId":1,"amount":%s}
                """.formatted(UUID.randomUUID(), orderId, UUID.randomUUID(), amount);
    }
}
