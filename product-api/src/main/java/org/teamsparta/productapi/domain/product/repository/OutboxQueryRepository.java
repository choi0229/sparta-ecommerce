package org.teamsparta.productapi.domain.product.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.teamsparta.productapi.domain.product.entity.OutboxEvent;
import org.teamsparta.productapi.global.enums.OutboxStatus;

import java.time.ZonedDateTime;
import java.util.List;

import static org.teamsparta.productapi.domain.product.entity.QOutboxEvent.outboxEvent;

@Repository
@RequiredArgsConstructor
public class OutboxQueryRepository {

    private static final int SKIP_LOCKED = -2;

    private final JPAQueryFactory queryFactory;

    /**
     * 발행 대상(PENDING이고 재시도 시각이 된 행)을 오래된 순으로 잠그며 조회한다.
     * 호출부(OutboxEventClaimer.claimBatch)의 트랜잭션 안에서만 의미가 있다.
     */
    public List<OutboxEvent> findBatchForPublish(ZonedDateTime now, int batchSize) {
        return queryFactory
                .selectFrom(outboxEvent)
                .where(
                        isPending(),
                        isRetryable(now)
                )
                .orderBy(outboxEvent.createdAt.asc())
                .limit(batchSize)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                // -2 = LockOptions.SKIP_LOCKED: 다른 Publisher가 잠근 행은 기다리지 않고 건너뛴다 (FOR UPDATE SKIP LOCKED)
                .setHint("jakarta.persistence.lock.timeout", SKIP_LOCKED)
                .fetch();
    }

    private BooleanExpression isPending() {
        return outboxEvent.status.eq(OutboxStatus.PENDING);
    }

    private BooleanExpression isRetryable(ZonedDateTime now) {
        return outboxEvent.nextRetryAt.isNull().or(outboxEvent.nextRetryAt.loe(now));
    }
}
