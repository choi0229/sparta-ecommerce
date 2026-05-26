package org.teamsparta.productapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.productapi.domain.product.entity.OutboxEvent;
import org.teamsparta.productapi.domain.product.entity.Product;
import org.teamsparta.productapi.domain.product.entity.ProductVariant;
import org.teamsparta.productapi.domain.product.event.ProductSnapShotReplyEvent;
import org.teamsparta.productapi.domain.product.event.dto.ProductSnapshotRequestResult;
import org.teamsparta.productapi.domain.product.repository.OutboxEventRepository;
import org.teamsparta.productapi.domain.product.repository.ProductVariantRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class ProductVariantServiceTest {

    @InjectMocks
    private org.teamsparta.productapi.domain.product.service.ProductVariantService productVariantService;

    @Mock
    private ProductVariantRepository productVariantRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    private final ObjectMapper realObjectMapper = new ObjectMapper();

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String IDEM_KEY = "idem-test-001";
    private static final Long USER_ID = 42L;

    @BeforeEach
    void injectRealObjectMapper() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                productVariantService, "objectMapper", realObjectMapper);
    }

    @Test
    @DisplayName("존재하지 않는 SKU 요청 시 실패 reply에 idemKey·userId·requestItem이 채워진다")
    void missingSkus_errorReplyContainsCorrelationFields() throws Exception {
        List<ProductSnapshotRequestResult.Item> requestItems =
                List.of(new ProductSnapshotRequestResult.Item("SKU-INVALID", 1));
        ProductSnapshotRequestResult event = new ProductSnapshotRequestResult(
                REQUEST_ID, "productSnapshot-requested-event", requestItems, IDEM_KEY, USER_ID, null);

        given(productVariantRepository.findBySkuIn(any())).willReturn(List.of());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        then(outboxEventRepository).should(times(0)).save(any()); // 아직 저장 전

        productVariantService.replyProductSnapshot(event);

        then(outboxEventRepository).should(times(1)).save(captor.capture());
        String payload = captor.getValue().getPayload();
        ProductSnapShotReplyEvent reply = realObjectMapper.readValue(payload, ProductSnapShotReplyEvent.class);

        assertThat(reply.isSuccess()).isFalse();
        assertThat(reply.getError()).contains("MISSING_SKU");
        assertThat(reply.getIdemKey()).isEqualTo(IDEM_KEY);
        assertThat(reply.getUserId()).isEqualTo(USER_ID);
        assertThat(reply.getRequestItem()).isNotNull();
        assertThat(reply.getRequestItem()).hasSize(1);
        assertThat(reply.getRequestItem().get(0).sku()).isEqualTo("SKU-INVALID");
        assertThat(reply.getRequestId()).isEqualTo(REQUEST_ID);
    }

    @Test
    @DisplayName("빈 SKU 목록 요청 시 실패 reply에 idemKey·userId가 채워진다")
    void emptySkus_errorReplyContainsCorrelationFields() throws Exception {
        List<ProductSnapshotRequestResult.Item> requestItems = List.of();
        ProductSnapshotRequestResult event = new ProductSnapshotRequestResult(
                REQUEST_ID, "productSnapshot-requested-event", requestItems, IDEM_KEY, USER_ID, null);

        productVariantService.replyProductSnapshot(event);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        then(outboxEventRepository).should(times(1)).save(captor.capture());
        String payload = captor.getValue().getPayload();
        ProductSnapShotReplyEvent reply = realObjectMapper.readValue(payload, ProductSnapShotReplyEvent.class);

        assertThat(reply.isSuccess()).isFalse();
        assertThat(reply.getError()).isEqualTo("EMPTY_SKUS");
        assertThat(reply.getIdemKey()).isEqualTo(IDEM_KEY);
        assertThat(reply.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("정상 SKU 요청 시 success=true이고 idemKey·userId·items가 모두 채워진다")
    void validSkus_okReplyContainsAllFields() throws Exception {
        List<ProductSnapshotRequestResult.Item> requestItems =
                List.of(new ProductSnapshotRequestResult.Item("SKU-001", 2));
        ProductSnapshotRequestResult event = new ProductSnapshotRequestResult(
                REQUEST_ID, "productSnapshot-requested-event", requestItems, IDEM_KEY, USER_ID, null);

        Product product = Product.builder().name("상품A").build();
        org.springframework.test.util.ReflectionTestUtils.setField(product, "id", 10L);
        ProductVariant variant = ProductVariant.builder()
                .sku("SKU-001").price(new BigDecimal("5000")).product(product)
                .optionJson(Map.of("color", "red")).build();
        org.springframework.test.util.ReflectionTestUtils.setField(variant, "id", 1L);

        given(productVariantRepository.findBySkuIn(List.of("SKU-001"))).willReturn(List.of(variant));

        productVariantService.replyProductSnapshot(event);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        then(outboxEventRepository).should(times(1)).save(captor.capture());
        String payload = captor.getValue().getPayload();
        ProductSnapShotReplyEvent reply = realObjectMapper.readValue(payload, ProductSnapShotReplyEvent.class);

        assertThat(reply.isSuccess()).isTrue();
        assertThat(reply.getError()).isNull();
        assertThat(reply.getIdemKey()).isEqualTo(IDEM_KEY);
        assertThat(reply.getUserId()).isEqualTo(USER_ID);
        assertThat(reply.getItems()).hasSize(1);
        assertThat(reply.getItems().get(0).getSku()).isEqualTo("SKU-001");
    }
}
