package org.teamsparta.orderapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.event.OrderEventPublisher;
import org.teamsparta.orderapi.domain.order.event.dto.ProductSnapshotReplyResult;
import org.teamsparta.orderapi.domain.order.repository.*;
import org.teamsparta.orderapi.domain.order.service.IdempotencyService;
import org.teamsparta.orderapi.domain.order.service.OrderTransactionalService;
import org.teamsparta.orderapi.domain.order.service.ProductSnapshotPendingStore;
import org.teamsparta.orderapi.domain.productProjection.repository.ProductProjectionRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.util.ReflectionTestUtils.invokeMethod;

@ExtendWith(MockitoExtension.class)
class OrderTransactionalServiceTest {

    @InjectMocks OrderTransactionalService service;

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock OrderSagaStateRepository sagaStateRepository;
    @Mock OrderEventPublisher orderEventPublisher;
    @Mock ProductProjectionRepository productProjectionRepository;
    @Mock ObjectMapper objectMapper;
    @Mock IdempotencyService idempotencyService;
    @Mock OutboxQueryRepository outboxQueryRepository;
    @Mock OutboxEventRepository outboxEventRepository;
    @Mock ProductSnapshotPendingStore productSnapshotPendingStore;
    @Mock KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @DisplayName("shippingAddress가 있으면 저장된 Orders에 recipientName·recipientAddress가 설정된다")
    void createOrderInternal_withShippingAddress_setsRecipientFields() throws Exception {
        ProductSnapshotReplyResult result = new ProductSnapshotReplyResult(
                UUID.randomUUID(), null, true, null,
                List.of(new ProductSnapshotReplyResult.ProductSnapshotItem(
                        "SKU-001", 1L, "상품A", 10L, BigDecimal.valueOf(1000), Map.of()
                )),
                List.of(new ProductSnapshotReplyResult.Item("SKU-001", 1)),
                "idem-addr-001",
                1L,
                new ProductSnapshotReplyResult.ShippingAddress("홍길동", "서울시 강남구 테헤란로 1")
        );

        IdempotencyRecord idemRecord = IdempotencyRecord.start("idem-addr-001", "some-hash");
        given(idempotencyService.startOrThrow(eq("idem-addr-001"), any())).willReturn(idemRecord);

        Orders mockSaved = Orders.createNew("O001", 1L);
        ReflectionTestUtils.setField(mockSaved, "id", 99L);
        ArgumentCaptor<Orders> orderCaptor = ArgumentCaptor.forClass(Orders.class);
        given(orderRepository.save(orderCaptor.capture())).willReturn(mockSaved);

        service.createOrderInternal(result, "idem-addr-001");

        Orders captured = orderCaptor.getValue();
        assertThat(captured.getRecipientName()).isEqualTo("홍길동");
        assertThat(captured.getRecipientAddress()).isEqualTo("서울시 강남구 테헤란로 1");
    }

    // ── hashRequest SHA-256 검증 ─────────────────────────────────────────────

    /** hashRequest 테스트용 최소 ProductSnapshotReplyResult 생성 헬퍼 */
    private ProductSnapshotReplyResult sampleResult(Long userId, String sku, int quantity) {
        return new ProductSnapshotReplyResult(
                UUID.randomUUID(), null, true, null,
                List.of(),
                List.of(new ProductSnapshotReplyResult.Item(sku, quantity)),
                "idem-key",
                userId,
                null
        );
    }

    @Test
    @DisplayName("hashRequest: 동일한 입력은 항상 동일한 SHA-256 hash를 반환한다")
    void hashRequest_sameInput_returnsSameHash() {
        ProductSnapshotReplyResult r1 = sampleResult(1L, "SKU-001", 2);
        ProductSnapshotReplyResult r2 = sampleResult(1L, "SKU-001", 2);

        String h1 = invokeMethod(service, "hashRequest", r1);
        String h2 = invokeMethod(service, "hashRequest", r2);

        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("hashRequest: userId가 다르면 다른 hash를 반환한다")
    void hashRequest_differentUserId_returnsDifferentHash() {
        ProductSnapshotReplyResult r1 = sampleResult(1L, "SKU-001", 2);
        ProductSnapshotReplyResult r2 = sampleResult(2L, "SKU-001", 2);

        String h1 = invokeMethod(service, "hashRequest", r1);
        String h2 = invokeMethod(service, "hashRequest", r2);

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("hashRequest: 반환값은 SHA-256 hex 형식 — 길이 64, 소문자 hex 문자만 포함")
    void hashRequest_returnsSha256HexFormat() {
        ProductSnapshotReplyResult result = sampleResult(1L, "SKU-001", 3);

        String hash = invokeMethod(service, "hashRequest", result);

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]+");
    }

    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shippingAddress가 null이면 Orders의 recipientName·recipientAddress는 null로 유지된다")
    void createOrderInternal_withNullShippingAddress_recipientFieldsRemainNull() throws Exception {
        ProductSnapshotReplyResult result = new ProductSnapshotReplyResult(
                UUID.randomUUID(), null, true, null,
                List.of(new ProductSnapshotReplyResult.ProductSnapshotItem(
                        "SKU-001", 1L, "상품A", 10L, BigDecimal.valueOf(1000), Map.of()
                )),
                List.of(new ProductSnapshotReplyResult.Item("SKU-001", 1)),
                "idem-null-001",
                1L,
                null
        );

        IdempotencyRecord idemRecord = IdempotencyRecord.start("idem-null-001", "some-hash");
        given(idempotencyService.startOrThrow(eq("idem-null-001"), any())).willReturn(idemRecord);

        Orders mockSaved = Orders.createNew("O002", 1L);
        ReflectionTestUtils.setField(mockSaved, "id", 100L);
        ArgumentCaptor<Orders> orderCaptor = ArgumentCaptor.forClass(Orders.class);
        given(orderRepository.save(orderCaptor.capture())).willReturn(mockSaved);

        service.createOrderInternal(result, "idem-null-001");

        Orders captured = orderCaptor.getValue();
        assertThat(captured.getRecipientName()).isNull();
        assertThat(captured.getRecipientAddress()).isNull();
    }
}
