package org.teamsparta.orderapi.domain.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.teamsparta.orderapi.domain.order.event.dto.ShipmentEventPayload;
import org.teamsparta.orderapi.domain.order.service.ShipmentStatusTransactionalService;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

@Component
@Slf4j
public class ShipmentEventConsumer {

    private final ObjectMapper objectMapper;
    private final ShipmentStatusTransactionalService shipmentStatusTransactionalService;
    private final Counter consumeSuccessCounter;
    private final Counter consumeFailedCounter;

    public ShipmentEventConsumer(ObjectMapper objectMapper,
                                 ShipmentStatusTransactionalService shipmentStatusTransactionalService,
                                 MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.shipmentStatusTransactionalService = shipmentStatusTransactionalService;
        this.consumeSuccessCounter = Counter.builder("order.shipment.event.consume")
                .tag("result", "success").register(meterRegistry);
        this.consumeFailedCounter = Counter.builder("order.shipment.event.consume")
                .tag("result", "failed").register(meterRegistry);
    }

    @KafkaListener(topics = "shipment-event", groupId = "${spring.application.name}")
    public void onShipmentEvent(String message) {
        log.info("Received shipment-event: {}", message);
        try {
            ShipmentEventPayload payload = objectMapper.readValue(message, ShipmentEventPayload.class);
            if (payload.eventId() == null || payload.orderId() == null || payload.status() == null) {
                log.warn("Skipping shipment-event: required field is null. eventId={}, orderId={}, status={}",
                        payload.eventId(), payload.orderId(), payload.status());
                return;
            }
            String idemKey = "shipment-event:" + payload.eventId();
            shipmentStatusTransactionalService.applyShipmentStatus(idemKey, payload);
            consumeSuccessCounter.increment();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse shipment-event: {}", message, e);
            consumeFailedCounter.increment();
        } catch (Exception e) {
            log.error("Failed to process shipment-event: {}", message, e);
            consumeFailedCounter.increment();
            throw new DomainException(DomainExceptionCode.EVENT_CONSUME_ERROR);
        }
    }
}
