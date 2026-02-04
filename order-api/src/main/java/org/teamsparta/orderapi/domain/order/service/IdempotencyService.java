package org.teamsparta.orderapi.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.repository.IdempotencyRepository;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRepository idempotencyRepository;

    public Optional<IdempotencyRecord> find(String idemKey){
        return idempotencyRepository.findById(idemKey);
    }

    public IdempotencyRecord start(String idemKey, String requestHash){
        return idempotencyRepository.save(IdempotencyRecord.start(idemKey, requestHash));
    }

    public void complete(String idemKey, Long orderId) {
        IdempotencyRecord idempotencyRecord = idempotencyRepository.findById(idemKey)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_IDEMPOTENCY));
        idempotencyRecord.complete(orderId);
        idempotencyRepository.save(idempotencyRecord);
    }
}
