package org.teamsparta.productapi.domain.product.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.teamsparta.productapi.global.exception.DomainException;
import org.teamsparta.productapi.global.exception.DomainExceptionCode;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductVariantPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topicName = "product-variant-event";
    private final ObjectMapper objectMapper;

    public void publisherVariantUpserted(ProductVariantEvent event){
        log.info("Kafka 메시지 전송 시도: {}", event.getSku());

        try{
            String jsonEvent = objectMapper.writeValueAsString(event);
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(topicName, event.getSku(), jsonEvent);

            future.whenComplete((result,ex)->{
                if(ex != null){
                    log.error("Failed to send message to topic: {}", topicName, ex);
                }
                log.info("Message sent successfully. topic={}, partition={}, offset={}",
                        topicName,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            });
        }catch(Exception e){
            log.error("JSON 직렬화 중 오류 발생: SKU={}", event.getSku(), e);
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }

    }
}
