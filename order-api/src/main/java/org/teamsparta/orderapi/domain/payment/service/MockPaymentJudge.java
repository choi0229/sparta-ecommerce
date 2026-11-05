package org.teamsparta.orderapi.domain.payment.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.random.RandomGenerator;

/** 실제 PG 연동이 아닌 Mock 결제의 성공 여부를 설정된 성공률(%)로 판정한다. */
@Component
public class MockPaymentJudge {

    private final int successRate;
    private final RandomGenerator random;

    @Autowired
    public MockPaymentJudge(@Value("${payment.mock.success-rate:100}") int successRate) {
        this(successRate, new Random());
    }

    public MockPaymentJudge(int successRate, RandomGenerator random) {
        if (successRate < 0 || successRate > 100) {
            throw new IllegalArgumentException("payment.mock.success-rate must be between 0 and 100: " + successRate);
        }
        this.successRate = successRate;
        this.random = random;
    }

    public boolean approve() {
        return random.nextInt(100) < successRate;
    }
}
