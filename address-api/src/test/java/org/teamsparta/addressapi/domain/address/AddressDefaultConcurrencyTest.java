package org.teamsparta.addressapi.domain.address;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.teamsparta.addressapi.domain.address.dto.AddressCreateRequest;
import org.teamsparta.addressapi.domain.address.entity.UserAddress;
import org.teamsparta.addressapi.domain.address.repository.UserAddressRepository;
import org.teamsparta.addressapi.domain.address.service.AddressService;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본 배송지 1개 정책 동시성 테스트.
 * PostgreSQL partial unique index(ux_user_address_default_active)에 의존하므로 Testcontainers를 사용한다.
 * H2는 WHERE 절 partial index를 지원하지 않아 대체 불가.
 */
@SpringBootTest
@Testcontainers
class AddressDefaultConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AddressService addressService;

    @Autowired
    private UserAddressRepository userAddressRepository;

    @BeforeEach
    void cleanUp() {
        userAddressRepository.deleteAll();
    }

    @Test
    @DisplayName("순차 요청 — 두 번째 isDefault=true 생성 후 기본 배송지는 1개, 두 번째 주소가 기본")
    void sequential_latestDefaultWins() {
        long userId = 10001L;

        addressService.create(new AddressCreateRequest(userId, "홍길동", "서울시 강남구", true));
        addressService.create(new AddressCreateRequest(userId, "홍길동", "부산시 해운대구", true));

        List<UserAddress> addresses = userAddressRepository.findByUserIdAndDeletedFalse(userId);
        long defaultCount = addresses.stream().filter(UserAddress::isDefault).count();

        assertThat(defaultCount).isEqualTo(1);
        assertThat(addresses.stream()
                .filter(UserAddress::isDefault)
                .findFirst()
                .map(UserAddress::getRecipientAddress)
                .orElse(""))
                .isEqualTo("부산시 해운대구");
    }

    @Test
    @DisplayName("순차 요청 — isDefault=true 수정 후 기본 배송지는 1개")
    void sequential_updateDefault_exactlyOneDefault() {
        long userId = 10003L;

        var addrA = addressService.create(new AddressCreateRequest(userId, "홍길동", "서울시 강남구", true));
        addressService.create(new AddressCreateRequest(userId, "홍길동", "부산시 해운대구", false));
        // 두 번째 주소를 기본으로 변경
        List<UserAddress> before = userAddressRepository.findByUserIdAndDeletedFalse(userId);
        Long addrBId = before.stream()
                .filter(a -> !a.isDefault())
                .findFirst()
                .map(UserAddress::getId)
                .orElseThrow();
        addressService.update(addrBId,
                new org.teamsparta.addressapi.domain.address.dto.AddressPatchRequest(null, null, true));

        List<UserAddress> addresses = userAddressRepository.findByUserIdAndDeletedFalse(userId);
        long defaultCount = addresses.stream().filter(UserAddress::isDefault).count();

        assertThat(defaultCount).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 요청 — partial unique index가 기본 배송지 중복을 차단한다")
    void concurrent_partialIndexPreventsDoubleDefault() throws Exception {
        long userId = 10002L;

        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger constraintViolationCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Void> task = () -> {
            startLatch.await();
            try {
                addressService.create(new AddressCreateRequest(userId, "수신자", "임시주소", true));
                successCount.incrementAndGet();
            } catch (DataIntegrityViolationException e) {
                // partial unique index 위반: 기본 배송지 중복 차단 확인
                constraintViolationCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(task);
        Future<Void> f2 = executor.submit(task);
        startLatch.countDown();

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        List<UserAddress> addresses = userAddressRepository.findByUserIdAndDeletedFalse(userId);
        long defaultCount = addresses.stream().filter(UserAddress::isDefault).count();

        // DB 정합성: 어떤 타이밍에서도 기본 배송지는 최대 1개
        assertThat(defaultCount).isLessThanOrEqualTo(1);
        // 최소 한 요청은 성공해야 한다
        assertThat(successCount.get()).isGreaterThanOrEqualTo(1);
        // 두 요청의 처리 결과가 모두 기록되었는지 확인
        assertThat(successCount.get() + constraintViolationCount.get()).isEqualTo(2);
    }
}
