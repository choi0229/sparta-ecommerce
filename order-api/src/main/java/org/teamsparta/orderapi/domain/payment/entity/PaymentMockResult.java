package org.teamsparta.orderapi.domain.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

/** Mock 결제 판정 결과. 저장은 PaymentMockResultRepository.insertIfAbsent로만 한다. */
@Table(name = "payment_mock_result")
@Entity
@Getter
@FieldDefaults(level = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentMockResult {

    @Id
    @Column(name = "order_id")
    Long orderId;

    @Column(name = "amount", nullable = false)
    BigDecimal amount;

    @Column(name = "success", nullable = false)
    boolean success;

    @Column(name = "failure_reason")
    String failureReason;

    @Column(name = "processed_at", nullable = false)
    ZonedDateTime processedAt;
}
