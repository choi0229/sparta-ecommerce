package org.teamsparta.orderapi.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.teamsparta.orderapi.domain.order.client.AddressServiceClient;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.event.dto.ShipmentEventPayload;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.domain.order.service.ShipmentStatusTransactionalService;
import org.teamsparta.orderapi.global.enums.ShipmentStatus;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 클라이언트 멱등키 계약을 실제 HTTP 계층과 PostgreSQL에서 검증한다.
 * Kafka 전송만 Mock이고, Outbox 저장과 PK 충돌, 트랜잭션 롤백은 실제 DB에서 일어난다.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "scheduler.outbox.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
@Testcontainers
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OrderIdempotencyIntegrationTest {

    private static final String BODY = """
            {"userId":1,"items":[{"sku":"SKU-A","quantity":1},{"sku":"SKU-B","quantity":2}],
             "shippingAddress":{"recipientName":"홍길동","recipientAddress":"서울시 강남구"}}
            """;
    private static final String REORDERED_ITEMS_BODY = """
            {"userId":1,"items":[{"sku":"SKU-B","quantity":2},{"sku":"SKU-A","quantity":1}],
             "shippingAddress":{"recipientName":"홍길동","recipientAddress":"서울시 강남구"}}
            """;
    private static final String ADDRESS_ID_BODY = """
            {"userId":1,"items":[{"sku":"SKU-A","quantity":1}],"addressId":7}
            """;
    private static final String SNAPSHOT_EVENT = "productSnapshot-requested-event";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("orderdb")
            .withUsername("root")
            .withPassword("rootpassword");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ShipmentStatusTransactionalService shipmentStatusTransactionalService;

    @MockBean
    private AddressServiceClient addressServiceClient;
    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;
    @MockBean
    private KafkaAdmin kafkaAdmin;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM idempotency_request");
        jdbcTemplate.update("DELETE FROM orders");
    }

    @Test
    @DisplayName("헤더가 없으면 400, 기록과 Outbox를 만들지 않는다")
    void missingHeader_returns400() throws Exception {
        assertThat(mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andReturn().getResponse().getStatus())
                .isEqualTo(400);
        assertNothingAccepted();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "shipment-event:abc"})
    @DisplayName("빈 키와 배송 이벤트 접두사 키는 400, 기록과 Outbox를 만들지 않는다")
    void invalidKey_returns400(String key) throws Exception {
        assertThat(send(key, BODY).getResponse().getStatus()).isEqualTo(400);
        assertNothingAccepted();
    }

    @Test
    @DisplayName("129자 키는 400, 128자 키는 접수된다 (경계값)")
    void keyLengthBoundary() throws Exception {
        assertThat(send("k".repeat(129), BODY).getResponse().getStatus()).isEqualTo(400);
        assertNothingAccepted();

        String maxKey = "k".repeat(128);
        assertThat(send(maxKey, BODY).getResponse().getStatus()).isBetween(200, 299);
        assertThat(recordCount(maxKey)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"userId\":1,\"items\":[{\"sku\":\"SKU-A\",\"quantity\":0}],\"addressId\":7}",
            "{\"userId\":1,\"items\":[{\"sku\":\"SKU-A\",\"quantity\":-1}],\"addressId\":7}",
            "{\"userId\":1,\"items\":[{\"sku\":\"SKU-A\"}],\"addressId\":7}",
            "{\"userId\":1,\"items\":[{\"sku\":\"\",\"quantity\":1}],\"addressId\":7}",
            "{\"userId\":1,\"items\":[{\"sku\":\"   \",\"quantity\":1}],\"addressId\":7}",
            "{\"userId\":1,\"items\":[{\"quantity\":1}],\"addressId\":7}",
            "{\"userId\":1,\"items\":[null],\"addressId\":7}",
            "{\"userId\":1,\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1},{\"sku\":\"SKU-A\",\"quantity\":2}],\"addressId\":7}",
            "{\"userId\":1,\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1},{\"sku\":\" SKU-A \",\"quantity\":2}],\"addressId\":7}"
    })
    @DisplayName("수량 0 이하, 빈 SKU, 중복 SKU는 접수 단계에서 400, 주소 조회·기록·Outbox 없음")
    void invalidItems_returns400WithoutSideEffects(String body) throws Exception {
        assertThat(send(newKey(), body).getResponse().getStatus()).isEqualTo(400);

        assertNothingAccepted();
        verify(addressServiceClient, never()).findById(anyLong(), anyLong());
    }

    @Test
    @DisplayName("같은 키·같은 요청의 순차 재전송 → 같은 응답, Outbox 1건, 기존 기록 불변")
    void sequentialRetry_returnsSameResultWithoutNewOutbox() throws Exception {
        String key = newKey();

        MvcResult first = send(key, BODY);
        assertThat(first.getResponse().getStatus()).isBetween(200, 299);
        assertThat(objectMapper.readTree(first.getResponse().getContentAsString()).at("/data/idemKey").asText())
                .isEqualTo(key);
        String hash = requestHash(key);
        assertThat(hash).hasSize(64);

        // 주문 생성이 끝난 뒤의 재전송도 기록을 되돌리면 안 된다
        jdbcTemplate.update("UPDATE idempotency_request SET status = 'COMPLETED' WHERE idem_key = ?", key);

        MvcResult second = send(key, BODY);

        assertSameResponse(first, second);
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(requestHash(key)).isEqualTo(hash);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM idempotency_request WHERE idem_key = ?", String.class, key)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("항목 순서만 다르면 같은 요청으로 본다")
    void reorderedItems_areTheSameRequest() throws Exception {
        String key = newKey();

        MvcResult first = send(key, BODY);
        MvcResult second = send(key, REORDERED_ITEMS_BODY);

        assertSameResponse(first, second);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"userId\":1,\"items\":[{\"sku\":\"SKU-A\",\"quantity\":9},{\"sku\":\"SKU-B\",\"quantity\":2}],\"shippingAddress\":{\"recipientName\":\"홍길동\",\"recipientAddress\":\"서울시 강남구\"}}",
            "{\"userId\":2,\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1},{\"sku\":\"SKU-B\",\"quantity\":2}],\"shippingAddress\":{\"recipientName\":\"홍길동\",\"recipientAddress\":\"서울시 강남구\"}}",
            "{\"userId\":1,\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1},{\"sku\":\"SKU-B\",\"quantity\":2}],\"shippingAddress\":{\"recipientName\":\"홍길동\",\"recipientAddress\":\"부산시 해운대구\"}}"
    })
    @DisplayName("같은 키·다른 요청(수량, 사용자, 주소)은 409, Outbox를 더 만들지 않는다")
    void sameKeyDifferentRequest_returns409(String differentBody) throws Exception {
        String key = newKey();
        send(key, BODY);

        assertThat(send(key, differentBody).getResponse().getStatus()).isEqualTo(409);

        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("주소가 삭제된 뒤 같은 키로 재전송해도 주소를 다시 조회하지 않고 기존 결과를 반환한다")
    void retryAfterAddressDeleted_returnsExistingResult() throws Exception {
        String key = newKey();
        given(addressServiceClient.findById(7L, 1L))
                .willReturn(new AddressServiceClient.AddressInfo("홍길동", "서울시 강남구"));
        MvcResult first = send(key, ADDRESS_ID_BODY);
        assertThat(first.getResponse().getStatus()).isBetween(200, 299);

        given(addressServiceClient.findById(7L, 1L))
                .willThrow(new DomainException(DomainExceptionCode.ADDRESS_NOT_FOUND));

        MvcResult second = send(key, ADDRESS_ID_BODY);

        assertSameResponse(first, second);
        verify(addressServiceClient, times(1)).findById(7L, 1L);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("주소 조회 실패는 기록을 남기지 않아, 같은 키로 다시 접수할 수 있다")
    void addressLookupFailure_leavesNoRecord() throws Exception {
        String key = newKey();
        given(addressServiceClient.findById(7L, 1L))
                .willThrow(new DomainException(DomainExceptionCode.ADDRESS_NOT_FOUND));

        assertThat(send(key, ADDRESS_ID_BODY).getResponse().getStatus()).isEqualTo(404);
        assertThat(recordCount(key)).isZero();
        assertThat(outboxCount()).isZero();

        willReturn(new AddressServiceClient.AddressInfo("홍길동", "서울시 강남구"))
                .given(addressServiceClient).findById(7L, 1L);
        assertThat(send(key, ADDRESS_ID_BODY).getResponse().getStatus()).isBetween(200, 299);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("직접 입력 주소로 접수하면 주소 서비스를 호출하지 않는다")
    void shippingAddress_doesNotCallAddressService() throws Exception {
        send(newKey(), BODY);

        verify(addressServiceClient, never()).findById(anyLong(), anyLong());
    }

    @RepeatedTest(10)
    @DisplayName("같은 키 동시 요청 2건 → Outbox 1건, 기록 1건, 두 응답 동일")
    void concurrentRequests_createOneOutboxAndSameResponses() throws Exception {
        String key = newKey();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> {
                start.await();
                return send(key, BODY);
            });
            Future<MvcResult> second = executor.submit(() -> {
                start.await();
                return send(key, BODY);
            });
            start.countDown();

            assertSameResponse(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertThat(recordCount(key)).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 트랜잭션이 같은 키를 먼저 커밋하면 PK 충돌을 처리해 기존 결과를 반환하고, Outbox를 남기지 않는다")
    void pkConflict_sameHash_returnsExistingResultAndRollsBackOutbox() throws Exception {
        String key = newKey();
        MvcResult reference = send(newKey(), BODY);
        String hash = jdbcTemplate.queryForObject("SELECT request_hash FROM idempotency_request LIMIT 1", String.class);
        jdbcTemplate.update("DELETE FROM outbox_event");

        MvcResult response = sendWhileAnotherTransactionHoldsKey(key, hash);

        assertThat(response.getResponse().getStatus()).isEqualTo(reference.getResponse().getStatus());
        assertThat(objectMapper.readTree(response.getResponse().getContentAsString()).at("/data/idemKey").asText())
                .isEqualTo(key);
        // 실패한 트랜잭션의 Outbox가 함께 롤백되어, 이 요청이 만든 이벤트는 없다
        assertThat(outboxCount()).isZero();
    }

    @Test
    @DisplayName("다른 트랜잭션이 같은 키를 다른 요청으로 먼저 커밋하면 PK 충돌 후 409를 반환한다")
    void pkConflict_differentHash_returns409() throws Exception {
        MvcResult response = sendWhileAnotherTransactionHoldsKey(newKey(), "f".repeat(64));

        assertThat(response.getResponse().getStatus()).isEqualTo(409);
        assertThat(outboxCount()).isZero();
    }

    @Test
    @DisplayName("Outbox 저장이 실패하면 멱등 기록도 함께 롤백되어 같은 키로 다시 접수할 수 있다")
    void outboxFailure_rollsBackIdempotencyRecord() throws Exception {
        String key = newKey();
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_outbox() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'outbox insert failed'; END; $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_outbox_insert BEFORE INSERT ON outbox_event
                FOR EACH ROW EXECUTE FUNCTION fail_outbox()
                """);
        try {
            assertThat(send(key, BODY).getResponse().getStatus()).isEqualTo(500);
            assertThat(recordCount(key)).isZero();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER fail_outbox_insert ON outbox_event");
            jdbcTemplate.execute("DROP FUNCTION fail_outbox()");
        }

        assertThat(send(key, BODY).getResponse().getStatus()).isBetween(200, 299);
        assertThat(recordCount(key)).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("배송 이벤트 경로: 기록 신규 생성과 중복 무시가 그대로 동작한다")
    void shipmentEvent_newRecordAndDuplicate() {
        Orders order = orderRepository.save(Orders.createNew("O-SHIP-1", 1L));
        String key = "shipment-event:" + UUID.randomUUID();

        shipmentStatusTransactionalService.applyShipmentStatus(key,
                new ShipmentEventPayload("e1", 1L, order.getId(), "SHIPPED", "발송"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM idempotency_request WHERE idem_key = ?", String.class, key)).isEqualTo("COMPLETED");

        shipmentStatusTransactionalService.applyShipmentStatus(key,
                new ShipmentEventPayload("e1", 1L, order.getId(), "DELIVERED", "배달"));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getShipmentStatus())
                .isEqualTo(ShipmentStatus.SHIPPED);
    }

    @Test
    @DisplayName("배송 이벤트 경로: PENDING으로 남은 기록을 이어서 처리한다 (기존 행 갱신)")
    void shipmentEvent_existingPendingRecordIsUpdated() {
        Orders order = orderRepository.save(Orders.createNew("O-SHIP-2", 1L));
        String key = "shipment-event:" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO idempotency_request (idem_key, request_hash, status) VALUES (?, ?, 'PENDING')",
                key, "h");

        shipmentStatusTransactionalService.applyShipmentStatus(key,
                new ShipmentEventPayload("e2", 1L, order.getId(), "SHIPPED", "발송"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM idempotency_request WHERE idem_key = ?", String.class, key)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT order_id FROM idempotency_request WHERE idem_key = ?", Long.class, key)).isEqualTo(order.getId());
    }

    private MvcResult sendWhileAnotherTransactionHoldsKey(String key, String hash) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            holder.setAutoCommit(false);
            try (PreparedStatement insert = holder.prepareStatement(
                    "INSERT INTO idempotency_request (idem_key, request_hash, status) VALUES (?, ?, 'PENDING')")) {
                insert.setString(1, key);
                insert.setString(2, hash);
                insert.executeUpdate();
            }

            Future<MvcResult> response = executor.submit(() -> send(key, BODY));
            Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """, Long.class) == 1);

            holder.commit();
            return response.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private MvcResult send(String key, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/orders")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return mockMvc.perform(request).andReturn();
    }

    private void assertSameResponse(MvcResult first, MvcResult second) throws Exception {
        assertThat(first.getResponse().getStatus()).isBetween(200, 299);
        assertThat(second.getResponse().getStatus()).isEqualTo(first.getResponse().getStatus());
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
    }

    private void assertNothingAccepted() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_request", Long.class)).isZero();
        assertThat(outboxCount()).isZero();
    }

    private long outboxCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?", Long.class, SNAPSHOT_EVENT);
    }

    private long recordCount(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_request WHERE idem_key = ?", Long.class, key);
    }

    private String requestHash(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT request_hash FROM idempotency_request WHERE idem_key = ?", String.class, key);
    }

    private String newKey() {
        return UUID.randomUUID().toString();
    }
}
