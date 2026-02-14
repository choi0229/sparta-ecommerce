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

    private final JPAQueryFactory queryFactory;

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
                // TODO: ? '3000' 대신 '-2'를 넣으면 대부분의 dialect에서 'SKIP LOCKED'로 해석
                .setHint("javax.persistence.lock.timeout", 3000)
                .fetch();
    }

    private BooleanExpression isPending() {
        return outboxEvent.status.eq(OutboxStatus.PENDING);
    }

    private BooleanExpression isRetryable(ZonedDateTime now) {
        return outboxEvent.nextRetryAt.isNull().or(outboxEvent.nextRetryAt.loe(now));
    }
}
