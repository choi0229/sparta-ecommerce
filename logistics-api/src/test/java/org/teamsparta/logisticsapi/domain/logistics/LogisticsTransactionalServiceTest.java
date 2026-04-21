package org.teamsparta.logisticsapi.domain.logistics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentCreateRequest;
import org.teamsparta.logisticsapi.domain.logistics.entity.IdempotencyRecord;
import org.teamsparta.logisticsapi.domain.logistics.entity.Shipment;
import org.teamsparta.logisticsapi.domain.logistics.repository.IdempotencyRecordRepository;
import org.teamsparta.logisticsapi.domain.logistics.repository.OutboxEventRepository;
import org.teamsparta.logisticsapi.domain.logistics.repository.ShipmentRepository;
import org.teamsparta.logisticsapi.domain.logistics.repository.ShipmentStatusHistoryRepository;
import org.teamsparta.logisticsapi.domain.logistics.service.LogisticsTransactionalService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class LogisticsTransactionalServiceTest {

    @Mock ShipmentRepository shipmentRepository;
    @Mock ShipmentStatusHistoryRepository historyRepository;
    @Mock OutboxEventRepository outboxEventRepository;
    @Mock IdempotencyRecordRepository idempotencyRecordRepository;
    @Mock ObjectMapper objectMapper;

    @InjectMocks
    LogisticsTransactionalService service;

    static final String IDEM_KEY = "order-event:event-001";
    static final ShipmentCreateRequest REQUEST =
            new ShipmentCreateRequest(1L, "홍길동", "서울시 강남구");

    Shipment existingShipment;

    @BeforeEach
    void setUp() {
        existingShipment = Shipment.create(1L, "홍길동", "서울시 강남구");
    }

    /**
     * shipmentRepository.save()는 실제 DB에서 IDENTITY 전략으로 ID를 할당하지만,
     * mock 환경에서는 ID가 null로 남는다. toPayload()에서 Map.of()에 null이 들어가면
     * NullPointerException이 발생하므로 doAnswer로 ID를 주입한다.
     */
    private void mockShipmentSaveWithId(long id) {
        willAnswer(invocation -> {
            Shipment s = invocation.getArgument(0);
            ReflectionTestUtils.setField(s, "id", id);
            return s;
        }).given(shipmentRepository).save(any(Shipment.class));
    }

    @Test
    @DisplayName("COMPLETED 레코드가 있으면 중복 이벤트로 보고 null을 반환한다")
    void alreadyCompleted_returnsNull() {
        IdempotencyRecord completed = IdempotencyRecord.start(IDEM_KEY);
        completed.complete();
        given(idempotencyRecordRepository.findById(IDEM_KEY)).willReturn(Optional.of(completed));

        Shipment result = service.createShipmentForOrderEvent(IDEM_KEY, REQUEST);

        assertThat(result).isNull();
        then(shipmentRepository).shouldHaveNoInteractions();
        then(historyRepository).shouldHaveNoInteractions();
        then(outboxEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("PENDING 레코드가 있고 배송이 이미 존재하면 레코드를 완료 처리하고 기존 배송을 반환한다")
    void pendingRecord_shipmentExists_recoversAndReturnsExisting() {
        IdempotencyRecord pending = IdempotencyRecord.start(IDEM_KEY);
        given(idempotencyRecordRepository.findById(IDEM_KEY)).willReturn(Optional.of(pending));
        given(shipmentRepository.existsByOrderId(1L)).willReturn(true);
        given(shipmentRepository.findByOrderId(1L)).willReturn(Optional.of(existingShipment));

        Shipment result = service.createShipmentForOrderEvent(IDEM_KEY, REQUEST);

        assertThat(result).isSameAs(existingShipment);
        assertThat(pending.isAlreadyProcessed()).isTrue();
        // 기존 배송을 그대로 사용하므로 새 배송을 생성하지 않는다
        then(shipmentRepository).should(never()).save(any(Shipment.class));
        then(historyRepository).shouldHaveNoInteractions();
        then(outboxEventRepository).shouldHaveNoInteractions();
        // 레코드 완료 처리를 위한 save 1회
        then(idempotencyRecordRepository).should(times(1)).save(pending);
    }

    @Test
    @DisplayName("PENDING 레코드가 있고 배송이 없으면 기존 레코드를 재사용해 배송을 생성하고 완료 처리한다")
    void pendingRecord_noShipment_reusesRecordAndCreatesShipment() throws Exception {
        IdempotencyRecord pending = IdempotencyRecord.start(IDEM_KEY);
        given(idempotencyRecordRepository.findById(IDEM_KEY)).willReturn(Optional.of(pending));
        given(shipmentRepository.existsByOrderId(1L)).willReturn(false);
        mockShipmentSaveWithId(100L);
        given(objectMapper.writeValueAsString(any())).willReturn("{}");

        Shipment result = service.createShipmentForOrderEvent(IDEM_KEY, REQUEST);

        assertThat(result).isNotNull();
        assertThat(result.getOrderId()).isEqualTo(1L);
        assertThat(pending.isAlreadyProcessed()).isTrue();
        // 신규 PENDING 저장 없이 기존 레코드 재사용 → save는 완료 처리 1회만
        then(idempotencyRecordRepository).should(times(1)).save(any(IdempotencyRecord.class));
        then(shipmentRepository).should(times(1)).save(any(Shipment.class));
        then(historyRepository).should(times(1)).save(any());
        then(outboxEventRepository).should(times(1)).save(any());
    }

    @Test
    @DisplayName("레코드가 없으면 PENDING 저장 후 배송을 생성하고 COMPLETED 처리한다")
    void noRecord_createsPendingThenShipmentThenCompletes() throws Exception {
        given(idempotencyRecordRepository.findById(IDEM_KEY)).willReturn(Optional.empty());
        mockShipmentSaveWithId(100L);
        given(objectMapper.writeValueAsString(any())).willReturn("{}");

        Shipment result = service.createShipmentForOrderEvent(IDEM_KEY, REQUEST);

        assertThat(result).isNotNull();
        assertThat(result.getOrderId()).isEqualTo(1L);
        // PENDING 저장 1회 + COMPLETED 저장 1회 = 총 2회
        then(idempotencyRecordRepository).should(times(2)).save(any(IdempotencyRecord.class));
        then(shipmentRepository).should(times(1)).save(any(Shipment.class));
        then(historyRepository).should(times(1)).save(any());
        then(outboxEventRepository).should(times(1)).save(any());
    }

    @Test
    @DisplayName("PENDING 레코드가 있고 같은 orderId 배송이 이미 존재하면 새 배송을 생성하지 않는다")
    void pendingRecord_duplicateOrderId_doesNotCreateNewShipment() {
        IdempotencyRecord pending = IdempotencyRecord.start(IDEM_KEY);
        given(idempotencyRecordRepository.findById(IDEM_KEY)).willReturn(Optional.of(pending));
        given(shipmentRepository.existsByOrderId(1L)).willReturn(true);
        given(shipmentRepository.findByOrderId(1L)).willReturn(Optional.of(existingShipment));

        Shipment result = service.createShipmentForOrderEvent(IDEM_KEY, REQUEST);

        assertThat(result).isSameAs(existingShipment);
        then(shipmentRepository).should(never()).save(any(Shipment.class));
    }
}
