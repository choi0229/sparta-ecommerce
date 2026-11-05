package org.teamsparta.orderapi.domain.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsparta.orderapi.domain.payment.entity.PaymentMockResult;

import java.math.BigDecimal;

public interface PaymentMockResultRepository extends JpaRepository<PaymentMockResult, Long> {

    /** 이미 판정된 주문이면 0을 반환한다. PK 충돌로 트랜잭션이 중단되지 않아 중복을 정상 흐름으로 처리할 수 있다. */
    @Modifying
    @Query(value = """
            INSERT INTO payment_mock_result (order_id, amount, success, failure_reason)
            VALUES (:orderId, :amount, :success, :failureReason)
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("orderId") Long orderId,
                       @Param("amount") BigDecimal amount,
                       @Param("success") boolean success,
                       @Param("failureReason") String failureReason);
}
