package org.teamsparta.logisticsapi.domain.logistics.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.teamsparta.logisticsapi.domain.logistics.entity.OutboxEvent;
import org.teamsparta.logisticsapi.global.enums.OutboxStatus;

import java.time.ZonedDateTime;
import java.util.List;

import static org.teamsparta.logisticsapi.domain.logistics.entity.QOutboxEvent.outboxEvent;

@Repository
@RequiredArgsConstructor
public class OutboxQueryRepository {

    private final JPAQueryFactory queryFactory;

    public List<OutboxEvent> findBatchForPublish(ZonedDateTime now, int batchSize) {
        return queryFactory
                .selectFrom(outboxEvent)
                .where(isPending(), isRetryable(now))
                .orderBy(outboxEvent.createdAt.asc())
                .limit(batchSize)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
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
