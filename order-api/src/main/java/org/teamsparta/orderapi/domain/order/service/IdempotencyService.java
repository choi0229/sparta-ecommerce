package org.teamsparta.orderapi.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.repository.IdempotencyRepository;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRepository idempotencyRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord startOrThrow(String key, String requestHash) {
        // 1) 있으면 검사
        var existing = idempotencyRepository.findById(key).orElse(null);
        if(existing != null){
            // 키는 같은데 내용 다름
            if(!existing.getRequestHash().equals(requestHash)){
                throw new DomainException(DomainExceptionCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST);
            }
            // 이미 성공 요청
            if("COMPLETED".equals(existing.getStatus())){
                return existing;
            }
            // 기록은 있는데 COMPLETED가 아님
            throw new DomainException(DomainExceptionCode.IDEMPOTENCY_IN_PROGRESS);
        }

        try{
            return idempotencyRepository.save(IdempotencyRecord.start(key, requestHash));
        }catch (DataIntegrityViolationException e){
            var again = idempotencyRepository.findById(key).orElseThrow();
            if(!again.getRequestHash().equals(requestHash)){
                throw new DomainException(DomainExceptionCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST);
            }
            if("COMPLETED".equals(again.getStatus()) && again.getOrderId() != null){
                return again;
            }
            throw new DomainException(DomainExceptionCode.IDEMPOTENCY_IN_PROGRESS);
        }
    }

    public void complete(String key, Long orderId) {
        IdempotencyRecord existing = idempotencyRepository.findById(key).orElseThrow();
        existing.complete(orderId);
        idempotencyRepository.save(existing);
    }
}
