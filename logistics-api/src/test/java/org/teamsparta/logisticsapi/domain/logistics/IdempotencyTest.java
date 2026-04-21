package org.teamsparta.logisticsapi.domain.logistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsparta.logisticsapi.domain.logistics.entity.IdempotencyRecord;
import org.teamsparta.logisticsapi.global.enums.IdempotencyStatus;

import static org.assertj.core.api.Assertions.*;

class IdempotencyTest {

    @Test
    @DisplayName("IdempotencyRecord를 생성하면 PENDING 상태다")
    void start_isPending() {
        IdempotencyRecord record = IdempotencyRecord.start("test-key");
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.PENDING);
        assertThat(record.isAlreadyProcessed()).isFalse();
    }

    @Test
    @DisplayName("complete() 호출 후 COMPLETED 상태가 된다")
    void complete_isCompleted() {
        IdempotencyRecord record = IdempotencyRecord.start("test-key");
        record.complete();

        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.isAlreadyProcessed()).isTrue();
        assertThat(record.getProcessedAt()).isNotNull();
    }
}
