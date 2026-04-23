package org.teamsparta.orderapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.orderapi.domain.order.event.ShipmentEventConsumer;
import org.teamsparta.orderapi.domain.order.event.dto.ShipmentEventPayload;
import org.teamsparta.orderapi.domain.order.service.ShipmentStatusTransactionalService;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ShipmentEventConsumerTest {

    @Mock ShipmentStatusTransactionalService shipmentStatusTransactionalService;

    private static final String VALID_JSON = """
            {
              "eventId": "evt-001",
              "shipmentId": 1,
              "orderId": 10,
              "status": "SHIPPED",
              "description": ""
            }
            """;

    private ObjectMapper realObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Test
    @DisplayName("유효한 JSON 수신 시 applyShipmentStatus가 올바른 idemKey로 1회 호출된다")
    void validJson_callsApplyShipmentStatusWithCorrectIdemKey() {
        ShipmentEventConsumer consumer = new ShipmentEventConsumer(realObjectMapper(), shipmentStatusTransactionalService);

        consumer.onShipmentEvent(VALID_JSON);

        then(shipmentStatusTransactionalService).should(times(1)).applyShipmentStatus(
                eq("shipment-event:evt-001"),
                eq(new ShipmentEventPayload("evt-001", 1L, 10L, "SHIPPED", ""))
        );
    }

    @Test
    @DisplayName("유효하지 않은 JSON 수신 시 JsonProcessingException을 catch하고 rethrow하지 않는다")
    void invalidJson_catchesJsonProcessingException_doesNotRethrow() throws JsonProcessingException {
        ObjectMapper mockMapper = mock(ObjectMapper.class);
        given(mockMapper.readValue(any(String.class), eq(ShipmentEventPayload.class)))
                .willThrow(new JsonProcessingException("invalid json") {});
        ShipmentEventConsumer consumer = new ShipmentEventConsumer(mockMapper, shipmentStatusTransactionalService);

        consumer.onShipmentEvent("{invalid}");

        then(shipmentStatusTransactionalService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("applyShipmentStatus에서 RuntimeException 발생 시 DomainException(EVENT_CONSUME_ERROR)을 던진다")
    void serviceThrowsRuntimeException_wrapsAsDomainException() {
        ShipmentEventConsumer consumer = new ShipmentEventConsumer(realObjectMapper(), shipmentStatusTransactionalService);
        willThrow(new RuntimeException("db error"))
                .given(shipmentStatusTransactionalService).applyShipmentStatus(any(), any());

        assertThatThrownBy(() -> consumer.onShipmentEvent(VALID_JSON))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getCode())
                        .isEqualTo(DomainExceptionCode.EVENT_CONSUME_ERROR.name()));
    }
}
