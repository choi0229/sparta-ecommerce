package org.teamsparta.logisticsapi.domain.logistics.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
@Slf4j
public class OrderEventConsumer {

    private final LogisticsTransactionalService transactionalService;
    private final ObjectMapper objectMapper;

    // [MVP] order-api의 order-create-event 토픽을 수신한다.
    // 현재 order-api 이벤트 payload에 recipientName/recipientAddress가 없으므로 null로 생성한다.
    // 배송지 정보는 추후 order-api 주문 요청/이벤트 확장 또는 별도 배송지 업데이트 API로 보강한다.
    @KafkaListener(topics = "order-create-event", groupId = "${spring.application.name}")
    public void onOrderEvent(String message) {
        log.info("Received order-create-event: {}", message);
        try {
            OrderCreatedPayload payload = objectMapper.readValue(message, OrderCreatedPayload.class);
            String idemKey = "order-create-event:" + payload.eventId();
            Shipment shipment = transactionalService.createShipmentForOrderEvent(
                    idemKey,
                    new ShipmentCreateRequest(payload.orderId(), null, null)
            );
            if (shipment != null) {
                log.info("Shipment created from order-create-event. orderId={}", payload.orderId());
            }
        } catch (JsonProcessingException e) {
            log.error("Fatal: Invalid JSON in order-create-event. message={}", message, e);
        } catch (Exception e) {
            log.error("Error processing order-create-event. message={}", message, e);
            throw new DomainException(DomainExceptionCode.EVENT_CONSUME_ERROR);
        }
    }

    // order-api OrderCreatedEvent 구조와 동일하게 맞춤 (1차 MVP 연결용)
    public record OrderCreatedPayload(
            String eventId,
            String eventType,
            Long orderId,
            UUID sagaId,
            Long userId,
            List<Item> items
    ) {
        public record Item(String sku, Integer quantity) {}
    }
}
