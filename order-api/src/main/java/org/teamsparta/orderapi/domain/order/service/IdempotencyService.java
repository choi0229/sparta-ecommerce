package org.teamsparta.orderapi.domain.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.repository.IdempotencyRepository;
import org.teamsparta.orderapi.global.enums.IdempotencyStatus;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final IdempotencyRepository idempotencyRepository;

    /** 호출자의 트랜잭션 안에서 기록을 바로 insert한다. 같은 키가 이미 있으면 PK 충돌로 실패한다. */
    @Transactional
    public void start(String key, String requestHash) {
        idempotencyRepository.saveAndFlush(IdempotencyRecord.start(key, requestHash));
    }

    /** 처리 대상 기록을 잠가 같은 키의 후속 처리를 직렬화한다. 접수 시 선기록되므로 없으면 시스템 오류다. */
    public IdempotencyRecord getForUpdate(String key) {
        return idempotencyRepository.findByIdForUpdate(key)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_IDEMPOTENCY));
    }

    public void complete(String key, Long orderId) {
        IdempotencyRecord existing = idempotencyRepository.findById(key).orElseThrow();
        existing.complete(orderId);
        idempotencyRepository.save(existing);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String key, String reason) {
        IdempotencyRecord existing = idempotencyRepository.findById(key)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_IDEMPOTENCY));
        if (existing.getStatus() == IdempotencyStatus.FAILED) {
            return; // 중복 이벤트 — 멱등 처리
        }
        if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
            log.warn("Product snapshot failure ignored: order already completed. idemKey={}", key);
            return;
        }
        existing.fail(reason);
        idempotencyRepository.save(existing);
        log.info("IdempotencyRecord marked FAILED. idemKey={}, reason={}", key, reason);
    }
}
