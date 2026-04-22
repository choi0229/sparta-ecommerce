package org.teamsparta.logisticsapi.domain.logistics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentCreateRequest;
import org.teamsparta.logisticsapi.domain.logistics.entity.Shipment;
import org.teamsparta.logisticsapi.domain.logistics.event.OrderEventConsumer;
import org.teamsparta.logisticsapi.domain.logistics.service.LogisticsTransactionalService;
import org.teamsparta.logisticsapi.global.exception.DomainException;
import org.teamsparta.logisticsapi.global.exception.DomainExceptionCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock LogisticsTransactionalService transactionalService;

    private static final String VALID_JSON = """
            {
              "eventId": "evt-001",
              "eventType": "ORDER_CREATED",
              "orderId": 1,
              "sagaId": "00000000-0000-0000-0000-000000000001",
              "userId": 42,
              "items": [{"sku": "SKU-A", "quantity": 2}]
            }
            """;

    private ObjectMapper realObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Test
    @DisplayName("유효한 JSON 수신 시 createShipmentForOrderEvent가 올바른 idemKey와 null 배송지로 1회 호출된다")
    void validJson_callsCreateShipmentWithCorrectIdemKeyAndNullAddress() {
        OrderEventConsumer consumer = new OrderEventConsumer(transactionalService, realObjectMapper());
        Shipment shipment = Shipment.create(1L, null, null);
        given(transactionalService.createShipmentForOrderEvent(any(), any())).willReturn(shipment);

        consumer.onOrderEvent(VALID_JSON);

        then(transactionalService).should(times(1)).createShipmentForOrderEvent(
                eq("order-create-event:evt-001"),
                eq(new ShipmentCreateRequest(1L, null, null))
        );
    }

    @Test
    @DisplayName("createShipmentForOrderEvent가 null을 반환해도 예외 없이 정상 종료된다 (중복 이벤트 케이스)")
    void validJson_nullReturnFromService_completesNormally() {
        OrderEventConsumer consumer = new OrderEventConsumer(transactionalService, realObjectMapper());
        given(transactionalService.createShipmentForOrderEvent(any(), any())).willReturn(null);

        consumer.onOrderEvent(VALID_JSON);

        then(transactionalService).should(times(1)).createShipmentForOrderEvent(any(), any());
    }

    @Test
    @DisplayName("유효하지 않은 JSON 수신 시 JsonProcessingException을 catch하고 rethrow하지 않는다")
    void invalidJson_catchesJsonProcessingException_doesNotRethrow() throws JsonProcessingException {
        ObjectMapper mockMapper = mock(ObjectMapper.class);
        given(mockMapper.readValue(any(String.class), eq(OrderEventConsumer.OrderCreatedPayload.class)))
                .willThrow(new JsonProcessingException("invalid json") {});
        OrderEventConsumer consumer = new OrderEventConsumer(transactionalService, mockMapper);

        consumer.onOrderEvent("{invalid}");

        then(transactionalService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("createShipmentForOrderEvent에서 RuntimeException 발생 시 DomainException(EVENT_CONSUME_ERROR)을 던진다")
    void serviceThrowsRuntimeException_wrapsAsDomainException() {
        OrderEventConsumer consumer = new OrderEventConsumer(transactionalService, realObjectMapper());
        given(transactionalService.createShipmentForOrderEvent(any(), any()))
                .willThrow(new RuntimeException("db error"));

        assertThatThrownBy(() -> consumer.onOrderEvent(VALID_JSON))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getCode())
                        .isEqualTo(DomainExceptionCode.EVENT_CONSUME_ERROR.name()));
    }
}
