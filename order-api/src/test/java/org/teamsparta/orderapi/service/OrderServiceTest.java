package org.teamsparta.orderapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.orderapi.domain.order.dto.request.CreateOrderRequest;
import org.teamsparta.orderapi.domain.order.dto.response.CreateOrderResponse;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.entity.OrderSagaState;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.entity.OutboxEvent;
import org.teamsparta.orderapi.domain.order.event.dto.ProductSnapshotReplyResult;
import org.teamsparta.orderapi.domain.order.event.dto.ProductSnapshotReplyResult.ProductSnapshotItem;
import org.teamsparta.orderapi.domain.order.repository.IdempotencyRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderItemRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderSagaStateRepository;
import org.teamsparta.orderapi.domain.order.repository.OutboxEventRepository;
import org.teamsparta.orderapi.domain.order.service.IdempotencyService;
import org.teamsparta.orderapi.domain.order.service.OrderService;
import org.teamsparta.orderapi.domain.order.service.OrderTransactionalService;
import org.teamsparta.orderapi.domain.order.service.ProductSnapshotPendingStore;
import org.teamsparta.orderapi.domain.productProjection.entity.ProductProjection;
import org.teamsparta.orderapi.domain.productProjection.repository.ProductProjectionRepository;
import org.teamsparta.orderapi.global.enums.IdempotencyStatus;
import org.teamsparta.orderapi.global.enums.OutboxStatus;
import org.teamsparta.orderapi.global.enums.ProductVariantStatus;
import org.teamsparta.orderapi.global.enums.ShipmentStatus;
import org.teamsparta.orderapi.global.enums.Status;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OrderServiceTest {

    @InjectMocks OrderService orderService;
    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock OrderSagaStateRepository sagaStateRepository;
    @Mock org.teamsparta.orderapi.domain.order.event.OrderEventPublisher orderEventPublisher;
    @Mock ProductProjectionRepository productProjectionRepository;
    @Mock ObjectMapper objectMapper;
    @Mock IdempotencyService idempotencyService;
    @Mock org.teamsparta.orderapi.domain.order.repository.OutboxQueryRepository outboxQueryRepository;
    @Mock OutboxEventRepository outboxEventRepository;
    @Mock ProductSnapshotPendingStore productSnapshotPendingStore;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock OrderTransactionalService orderTransactionalService;
    @Mock IdempotencyRepository idempotencyRepository;

    private static final String IDEM_KEY = "idem-key-001";

    @Test
    @DisplayName("idem 레코드 없음 — status=PENDING, shipmentStatus=null 반환")
    void getOrderStatus_noRecord_returnsPendingWithNullShipmentStatus() {
        given(idempotencyRepository.findById(IDEM_KEY)).willReturn(Optional.empty());

        OrderStatusResponse response = orderService.getOrderStatus(IDEM_KEY);

        assertThat(response.idemKey()).isEqualTo(IDEM_KEY);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.orderId()).isNull();
        assertThat(response.shipmentStatus()).isNull();
        then(orderRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("idem COMPLETED + 주문 shipmentStatus=SHIPPED — SHIPPED 반환")
    void getOrderStatus_completedWithShipment_returnsShipmentStatus() {
        IdempotencyRecord record = IdempotencyRecord.start(IDEM_KEY, IDEM_KEY);
        record.complete(10L);

        Orders order = Orders.createNew("O001", 42L);
        ReflectionTestUtils.setField(order, "id", 10L);
        order.updateShipmentStatus(ShipmentStatus.SHIPPED);

        given(idempotencyRepository.findById(IDEM_KEY)).willReturn(Optional.of(record));
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        OrderStatusResponse response = orderService.getOrderStatus(IDEM_KEY);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.orderId()).isEqualTo(10L);
        assertThat(response.shipmentStatus()).isEqualTo("SHIPPED");
    }

    @Test
    @DisplayName("idem COMPLETED + 주문 shipmentStatus 미설정 — shipmentStatus=null 반환")
    void getOrderStatus_completedOrderNoShipment_returnsNullShipmentStatus() {
        IdempotencyRecord record = IdempotencyRecord.start(IDEM_KEY, IDEM_KEY);
        record.complete(10L);

        Orders order = Orders.createNew("O001", 42L);
        ReflectionTestUtils.setField(order, "id", 10L);

        given(idempotencyRepository.findById(IDEM_KEY)).willReturn(Optional.of(record));
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        OrderStatusResponse response = orderService.getOrderStatus(IDEM_KEY);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.orderId()).isEqualTo(10L);
        assertThat(response.shipmentStatus()).isNull();
    }

    @Test
    @DisplayName("idem PENDING + orderId 비정상 세팅 — orderRepository 조회하되 shipmentStatus=null 반환")
    void getOrderStatus_pendingRecordWithOrderId_queriesOrderAndReturnsNull() {
        IdempotencyRecord record = IdempotencyRecord.start(IDEM_KEY, IDEM_KEY);
        ReflectionTestUtils.setField(record, "orderId", 10L);

        Orders order = Orders.createNew("O001", 42L);
        ReflectionTestUtils.setField(order, "id", 10L);

        given(idempotencyRepository.findById(IDEM_KEY)).willReturn(Optional.of(record));
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        OrderStatusResponse response = orderService.getOrderStatus(IDEM_KEY);

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.orderId()).isEqualTo(10L);
        assertThat(response.shipmentStatus()).isNull();
    }

//
//    @InjectMocks
//    private OrderService orderService;
//
//    @Mock
//    private OrderTransactionalService orderTransactionalService; // Mock으로 주입
//    @Mock
//    private ProductSnapshotPendingStore productSnapshotPendingStore;
//    @Mock
//    private KafkaTemplate<String, String> kafkaTemplate;
//    @Mock
//    private ObjectMapper objectMapper;
//
//    private OrderTransactionalService txService;
//
//    @Mock
//    private OrderRepository orderRepository;
//    @Mock
//    private OrderItemRepository orderItemRepository;
//    @Mock
//    private OrderSagaStateRepository sagaStateRepository;
//    @Mock
//    private IdempotencyService idempotencyService;
//    @Mock
//    private OutboxEventRepository outboxEventRepository;
//    @Mock
//    private ObjectMapper txObjectMapper;
//
//    private CreateOrderRequest request;
//    private final String IDEM_KEY = "idem-key-123";
//    private Map<String, ProductSnapshotItem> mockBySku;
//
//    @BeforeEach
//    void setUp() {
//        request = new CreateOrderRequest(
//                1L, List.of(new CreateOrderRequest.Item("SKU-001", 2))
//        );
//
//        ProductSnapshotItem item = new ProductSnapshotItem(
//                "SKU-001", 1L, "MacBook", 1L,
//                BigDecimal.valueOf(2000000), Map.of()
//        );
//        mockBySku = Map.of("SKU-001", item);
//
//        // txService 수동 생성 및 Mock 주입
//        txService = new OrderTransactionalService(
//                orderRepository,
//                orderItemRepository,
//                sagaStateRepository,
//                null, // OrderEventPublisher
//                null, // ProductProjectionRepository
//                txObjectMapper,
//                idempotencyService,
//                null, // OutboxQueryRepository
//                outboxEventRepository,
//                null, // ProductSnapshotPendingStore
//                null  // KafkaTemplate
//        );
//    }
//
//    private void mockFetchBySkus() {
//        ProductSnapshotReplyResult replyResult = new ProductSnapshotReplyResult(
//                UUID.randomUUID(),
//                "",
//                true,
//                null,
//                List.of(new ProductSnapshotItem(
//                        "SKU-001", 1L, "MacBook", 1L, BigDecimal.valueOf(2000000), Map.of()
//                ))
//        );
//        CompletableFuture<ProductSnapshotReplyResult> future = CompletableFuture.completedFuture(replyResult);
//        given(productSnapshotPendingStore.register(any(UUID.class))).willReturn(future);
//        try {
//            given(objectMapper.writeValueAsString(any())).willReturn("{}");
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//    // ── OrderService 테스트 ──────────────────────────────────────
//
//    @Test
//    @DisplayName("주문 생성 성공 - fetchBySkus 후 orderTransactionalService 호출 확인")
//    void createOrder_success() {
//        // given
//        mockFetchBySkus();
//        CreateOrderResponse expectedResponse = new CreateOrderResponse(100L, "O123", null);
//        given(orderTransactionalService.createOrderInternal(any(), anyString(), anyMap()))
//                .willReturn(expectedResponse);
//
//        // when
//        CreateOrderResponse response = orderService.createOrder(request, IDEM_KEY);
//
//        // then
//        assertThat(response.orderId()).isEqualTo(100L);
//        verify(orderTransactionalService).createOrderInternal(eq(request), eq(IDEM_KEY), anyMap());
//    }
//
//    @Test
//    @DisplayName("items가 null/empty면 NOT_FOUND_ITEMS")
//    void createOrder_emptyItems_throw() {
//        CreateOrderRequest req1 = new CreateOrderRequest(1L, null);
//        CreateOrderRequest req2 = new CreateOrderRequest(1L, List.of());
//
//        assertThatThrownBy(() -> orderService.createOrder(req1, IDEM_KEY))
//                .isInstanceOf(DomainException.class)
//                .hasMessage(DomainExceptionCode.NOT_FOUND_ITEMS.getMessage());
//
//        assertThatThrownBy(() -> orderService.createOrder(req2, IDEM_KEY))
//                .isInstanceOf(DomainException.class)
//                .hasMessage(DomainExceptionCode.NOT_FOUND_ITEMS.getMessage());
//    }
//
//    @Test
//    @DisplayName("Kafka 응답 타임아웃 시 PRODUCT_SNAPSHOT_NOT_READY 예외")
//    void createOrder_kafkaTimeout_throw() {
//        // given
//        CompletableFuture<ProductSnapshotReplyResult> future = new CompletableFuture<>();
//        given(productSnapshotPendingStore.register(any(UUID.class))).willReturn(future);
//        try {
//            given(objectMapper.writeValueAsString(any())).willReturn("{}");
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
//
//        // when & then
//        assertThatThrownBy(() -> orderService.createOrder(request, IDEM_KEY))
//                .isInstanceOf(DomainException.class)
//                .hasMessage(DomainExceptionCode.PRODUCT_SNAPSHOT_NOT_READY.getMessage());
//
//        verify(orderTransactionalService, never()).createOrderInternal(any(), any(), any());
//    }
//
//    // ── OrderTransactionalService 테스트 ────────────────────────
//
//    @Test
//    @DisplayName("멱등성 - 이미 완료된 주문이면 기존 주문 반환")
//    void createOrderInternal_already_completed() {
//        // given
//        Long existingOrderId = 100L;
//        IdempotencyRecord completedRecord = IdempotencyRecord.start(IDEM_KEY, "requestHash");
//        ReflectionTestUtils.setField(completedRecord, "status", IdempotencyStatus.COMPLETED);
//        ReflectionTestUtils.setField(completedRecord, "orderId", existingOrderId);
//
//        Orders existingOrder = Orders.createNew("O-EXIST", 1L);
//        ReflectionTestUtils.setField(existingOrder, "id", existingOrderId);
//
//        given(idempotencyService.startOrThrow(eq(IDEM_KEY), anyString())).willReturn(completedRecord);
//        given(orderRepository.findById(existingOrderId)).willReturn(Optional.of(existingOrder));
//
//        // when
//        CreateOrderResponse response = txService.createOrderInternal(request, IDEM_KEY, mockBySku);
//
//        // then
//        assertThat(response.orderId()).isEqualTo(existingOrderId);
//        verify(orderRepository, never()).save(any(Orders.class));
//        verify(outboxEventRepository, never()).save(any());
//    }
//
//    @Test
//    @DisplayName("멱등성 - IN_PROGRESS 요청이면 예외 발생")
//    void createOrderInternal_fail_when_already_in_progress() {
//        // given
//        given(idempotencyService.startOrThrow(eq(IDEM_KEY), anyString()))
//                .willThrow(new DomainException(DomainExceptionCode.CONCURRENT_DATA_CONFLICT));
//
//        // when & then
//        assertThatThrownBy(() -> txService.createOrderInternal(request, IDEM_KEY, mockBySku))
//                .isInstanceOf(DomainException.class)
//                .hasMessage(DomainExceptionCode.CONCURRENT_DATA_CONFLICT.getMessage());
//
//        then(orderRepository).should(never()).save(any());
//        then(outboxEventRepository).should(never()).save(any());
//    }
//}

//    @Test
//    @DisplayName("주문 생성 성공 - 멱등성 및 outbox 저장 확인")
//    void createOrder_success() throws IOException {
//        // given
//        IdempotencyRecord idemRecord = IdempotencyRecord.start(IDEM_KEY, "requestHash");
//        ProductProjection productProjection = org.teamsparta.orderapi.domain.productProjection.entity.ProductProjection.builder()
//                .sku("SKU-001")
//                .productName("MacBook")
//                .price(BigDecimal.valueOf(2000000))
//                .status(ProductVariantStatus.ACTIVE)
//                .optionJson(Map.of())
//                .categoryPath("IT")
//                .build();
//
//        given(idempotencyService.startOrThrow(eq(IDEM_KEY), anyString())).willReturn(idemRecord);
//        given(productProjectionRepository.findBySkuIn(anyList())).willReturn(List.of(productProjection));
//
//        Orders order = Orders.createNew("0123", 1L);
//        ReflectionTestUtils.setField(order, "id", 100L);
//        given(orderRepository.save(any(Orders.class))).willReturn(order);
//
//        given(objectMapper.writeValueAsString(any())).willReturn("{}");
//
//        // when
//        CreateOrderResponse response = orderService.createOrder(request, IDEM_KEY);
//
//        // then
//        assertThat(response.orderId()).isEqualTo(100L);
//
//        verify(orderRepository).save(any(Orders.class));
//        verify(sagaStateRepository, times(2)).save(any(OrderSagaState.class));
//        verify(orderRepository, times(1)).save(any(Orders.class));
//        verify(idempotencyService).complete(IDEM_KEY, 100L);
//
//        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
//        // outbox 검증
//        verify(outboxEventRepository).save(outboxCaptor.capture());
//        OutboxEvent outbox = outboxCaptor.getValue();
//
//        assertThat(outbox.getAggregateType()).isEqualTo("Orders");
//        assertThat(outbox.getAggregateId()).isEqualTo("100");
//        assertThat(outbox.getEventType()).isEqualTo("order-create-event");
//        assertThat(outbox.getPayload()).isEqualTo("{}");
//        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
//    }
//
//    @Test
//    @DisplayName("멱등성 검증 - 이미 완료된 주문 요청시 기존 주문 정보 반환")
//    void createOrder_already_completed(){
//        // given
//        Long existingOrderId = 100L;
//        IdempotencyRecord completedRecord = IdempotencyRecord.start(IDEM_KEY, "requestHash");
//        ReflectionTestUtils.setField(completedRecord, "status", IdempotencyStatus.COMPLETED);
//        ReflectionTestUtils.setField(completedRecord, "orderId", existingOrderId);
//
//        Orders existingOrder = Orders.createNew("O-EXIST", 1L);
//        ReflectionTestUtils.setField(existingOrder, "id", existingOrderId);
//
//        given(idempotencyService.startOrThrow(eq(IDEM_KEY), anyString())).willReturn(completedRecord);
//        given(orderRepository.findById(existingOrderId)).willReturn(Optional.of(existingOrder));
//
//        // when
//        CreateOrderResponse response = orderService.createOrder(request, IDEM_KEY);
//
//        // then
//        assertThat(response.orderId()).isEqualTo(existingOrderId);
//        verify(orderRepository, never()).save(any());
//        verify(orderItemRepository, never()).saveAll(anyList());
//        verify(outboxEventRepository, never()).save(any());
//        verify(idempotencyService, never()).complete(anyString(), anyLong());
//        verify(productProjectionRepository, never()).findBySkuIn(anyList());
//    }
//
//    @Test
//    @DisplayName("items가 null/empty면 NOT_FOUND_ITEMS")
//    void createOrder_emptyItems_throw() {
//        CreateOrderRequest req1 = new CreateOrderRequest(1L, null);
//        CreateOrderRequest req2 = new CreateOrderRequest(1L, List.of());
//
//        assertThatThrownBy(() -> orderService.createOrder(req1, IDEM_KEY))
//                .isInstanceOf(DomainException.class)
//                .hasMessage(DomainExceptionCode.NOT_FOUND_ITEMS.getMessage());
//
//        assertThatThrownBy(() -> orderService.createOrder(req2, IDEM_KEY))
//                .isInstanceOf(DomainException.class)
//                .hasMessage(DomainExceptionCode.NOT_FOUND_ITEMS.getMessage());
//    }
//
//    @Test
//    @DisplayName("멱등성 검증 - 이미 진행 중인 요청(IN_PROGRESS)이 오면 예외 발생")
//    void createOrder_fail_when_already_in_progress() {
//        // given
//        given(idempotencyService.startOrThrow(eq(IDEM_KEY), anyString()))
//                .willThrow(new DomainException(DomainExceptionCode.CONCURRENT_DATA_CONFLICT));
//
//        // when & then
//        assertThatThrownBy(() -> orderService.createOrder(request, IDEM_KEY))
//                .isInstanceOf(DomainException.class)
//                .hasMessage(DomainExceptionCode.CONCURRENT_DATA_CONFLICT.getMessage());
//
//        then(productProjectionRepository).shouldHaveNoInteractions();
//
//        then(orderRepository).should(never()).save(any());
//        then(orderItemRepository).should(never()).saveAll(any());
//        then(sagaStateRepository).should(never()).save(any());
//
//        then(outboxEventRepository).should(never()).save(any());
//        then(idempotencyService).should(never()).complete(anyString(), anyLong());
//
//        then(objectMapper).shouldHaveNoInteractions();
//    }
//
//    @Test
//    @DisplayName("실패 - 상품 상태가 ACTIVE가 아니면 주문 생성 불가")
//    void createOrder_inactive_fail() {
//        // given
//        given(idempotencyService.startOrThrow(eq(IDEM_KEY), anyString()))
//                .willReturn(IdempotencyRecord.start(IDEM_KEY, "requestHash"));
//
//        ProductProjection inactiveProjection = ProductProjection.builder()
//                .sku("SKU-001")
//                .status(ProductVariantStatus.INACTIVE)
//                .price(BigDecimal.valueOf(1000))
//                .productName("Product-001")
//                .build();
//
//        given(productProjectionRepository.findBySkuIn(anyList())).willReturn(List.of(inactiveProjection));
//
//        Orders order = Orders.createNew("0123", 1L);
//        ReflectionTestUtils.setField(order, "id", 100L);
//        given(orderRepository.save(any(Orders.class))).willReturn(order);
//
//        // when & then
//        assertThatThrownBy(() -> orderService.createOrder(request, IDEM_KEY))
//                .isInstanceOf(DomainException.class)
//                .hasMessage(DomainExceptionCode.PRODUCT_INACTIVE.getMessage());
//
//        then(outboxEventRepository).should(never()).save(any());
//    }

}