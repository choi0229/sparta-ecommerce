package org.teamsparta.orderapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.orderapi.domain.order.event.OrderEventConsumer;
import org.teamsparta.orderapi.domain.order.service.OrderSagaService;
import org.teamsparta.orderapi.domain.order.service.OrderTransactionalService;
import org.teamsparta.orderapi.domain.order.service.ProductSnapshotPendingStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock OrderTransactionalService orderTransactionalService;
    @Mock OrderSagaService orderSagaService;
    @Mock ProductSnapshotPendingStore productSnapshotPendingStore;

    private OrderEventConsumer consumer;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        consumer = new OrderEventConsumer(mapper, orderSagaService, productSnapshotPendingStore, orderTransactionalService);
    }

    @Test
    @DisplayName("success=false인 응답 수신 시 failOrder()가 idemKey와 error message로 호출된다")
    void successFalse_callsFailOrder_withErrorMessage() {
        String message = """
                {
                  "requestId": "00000000-0000-0000-0000-000000000001",
                  "success": false,
                  "error": "MISSING_SKU=[SKU-UNKNOWN]",
                  "items": null,
                  "requestItem": [{"sku":"SKU-UNKNOWN","quantity":1}],
                  "idemKey": "idem-fail-001",
                  "userId": 1
                }
                """;

        consumer.ProductSnapshotReplyEvent(message);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        then(orderTransactionalService).should(times(1)).failOrder(keyCaptor.capture(), reasonCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("idem-fail-001");
        assertThat(reasonCaptor.getValue()).isEqualTo("MISSING_SKU=[SKU-UNKNOWN]");

        then(orderTransactionalService).should(times(0)).createOrderInternal(any(), any());
    }

    @Test
    @DisplayName("success=true이고 error=null이면 createOrderInternal()이 호출되고 failOrder()는 호출되지 않는다")
    void successTrue_callsCreateOrderInternal_notFailOrder() {
        String message = """
                {
                  "requestId": "00000000-0000-0000-0000-000000000002",
                  "success": true,
                  "error": null,
                  "items": [
                    {"sku":"SKU-001","variantId":1,"productName":"상품A","productId":10,"price":1000,"optionJson":{}}
                  ],
                  "requestItem": [{"sku":"SKU-001","quantity":2}],
                  "idemKey": "idem-ok-001",
                  "userId": 1
                }
                """;

        consumer.ProductSnapshotReplyEvent(message);

        then(orderTransactionalService).should(times(1)).createOrderInternal(any(), any());
        then(orderTransactionalService).should(times(0)).failOrder(any(), any());
    }

    @Test
    @DisplayName("error 필드가 non-null이면 success=true여도 failOrder()가 호출된다")
    void errorNonNull_callsFailOrder_evenIfSuccessTrue() {
        String message = """
                {
                  "requestId": "00000000-0000-0000-0000-000000000003",
                  "success": true,
                  "error": "PARTIAL_FAILURE",
                  "items": null,
                  "requestItem": [{"sku":"SKU-001","quantity":1}],
                  "idemKey": "idem-err-001",
                  "userId": 1
                }
                """;

        consumer.ProductSnapshotReplyEvent(message);

        then(orderTransactionalService).should(times(1)).failOrder(any(), any());
        then(orderTransactionalService).should(times(0)).createOrderInternal(any(), any());
    }

    @Test
    @DisplayName("JSON 파싱 실패 시 failOrder()와 createOrderInternal() 모두 호출되지 않는다")
    void invalidJson_callsNothing() {
        consumer.ProductSnapshotReplyEvent("{invalid json}");

        then(orderTransactionalService).shouldHaveNoInteractions();
    }
}
