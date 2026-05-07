package org.teamsparta.logisticsapi.domain.logistics.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentCreateRequest;
import org.teamsparta.logisticsapi.domain.logistics.entity.Shipment;
import org.teamsparta.logisticsapi.domain.logistics.service.LogisticsTransactionalService;
import org.teamsparta.logisticsapi.global.exception.DomainException;
import org.teamsparta.logisticsapi.global.exception.DomainExceptionCode;

import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class OrderEventConsumer {

    private final LogisticsTransactionalService transactionalService;
    private final ObjectMapper objectMapper;
    private final Counter consumeSuccessCounter;
    private final Counter consumeFailedCounter;

    public OrderEventConsumer(LogisticsTransactionalService transactionalService,
                              ObjectMapper objectMapper,
                              MeterRegistry meterRegistry) {
        this.transactionalService = transactionalService;
        this.objectMapper = objectMapper;
        this.consumeSuccessCounter = Counter.builder("logistics.order.event.consume")
                .tag("result", "success").register(meterRegistry);
        this.consumeFailedCounter = Counter.builder("logistics.order.event.consume")
                .tag("result", "failed").register(meterRegistry);
    }

    @KafkaListener(topics = "order-create-event", groupId = "${spring.application.name}")
    public void onOrderEvent(String message) {
        log.info("Received order-create-event: {}", message);
        try {
            OrderCreatedPayload payload = objectMapper.readValue(message, OrderCreatedPayload.class);
            String idemKey = "order-create-event:" + payload.eventId();
            Shipment shipment = transactionalService.createShipmentForOrderEvent(
                    idemKey,
                    new ShipmentCreateRequest(payload.orderId(), payload.recipientName(), payload.recipientAddress())
            );
            if (shipment != null) {
                log.info("Shipment created from order-create-event. orderId={}", payload.orderId());
            }
            consumeSuccessCounter.increment();
        } catch (JsonProcessingException e) {
            log.error("Fatal: Invalid JSON in order-create-event. message={}", message, e);
            consumeFailedCounter.increment();
        } catch (Exception e) {
            log.error("Error processing order-create-event. message={}", message, e);
            consumeFailedCounter.increment();
            throw new DomainException(DomainExceptionCode.EVENT_CONSUME_ERROR);
        }
    }

    // order-api OrderCreatedEvent 구조와 동일하게 맞춤
    public record OrderCreatedPayload(
            String eventId,
            String eventType,
            Long orderId,
            UUID sagaId,
            Long userId,
            List<Item> items,
            String recipientName,
            String recipientAddress
    ) {
        public record Item(String sku, Integer quantity) {}
    }
}
