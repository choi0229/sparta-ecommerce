package org.teamsparta.orderapi.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.teamsparta.orderapi.domain.payment.service.MockPaymentJudge;

import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPaymentJudgeTest {

    /** nextInt(100)이 항상 지정한 값을 반환하는 난수 생성기 */
    private RandomGenerator fixed(int value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int nextInt(int bound) {
                assertThat(bound).isEqualTo(100);
                return value;
            }
        };
    }

    @ParameterizedTest(name = "rate={0}, random={1} → {2}")
    @CsvSource({
            "0, 0, false",
            "0, 99, false",
            "100, 0, true",
            "100, 99, true",
            "90, 89, true",
            "90, 90, false",
            "1, 0, true",
            "1, 1, false"
    })
    @DisplayName("난수가 성공률 미만일 때만 승인한다 (경계값)")
    void approve_boundary(int rate, int random, boolean expected) {
        assertThat(new MockPaymentJudge(rate, fixed(random)).approve()).isEqualTo(expected);
    }

    @Test
    @DisplayName("성공률 0이면 항상 실패, 100이면 항상 성공한다 (실제 난수)")
    void approve_zeroAndHundred_withRealRandom() {
        MockPaymentJudge never = new MockPaymentJudge(0);
        MockPaymentJudge always = new MockPaymentJudge(100);

        for (int i = 0; i < 1000; i++) {
            assertThat(never.approve()).isFalse();
            assertThat(always.approve()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101})
    @DisplayName("0~100을 벗어난 성공률은 기동 시 거부한다")
    void invalidRate_isRejected(int rate) {
        assertThatThrownBy(() -> new MockPaymentJudge(rate)).isInstanceOf(IllegalArgumentException.class);
    }
}
