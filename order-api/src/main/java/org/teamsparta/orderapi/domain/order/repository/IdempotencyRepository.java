package org.teamsparta.orderapi.domain.order.repository;


import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from IdempotencyRecord r where r.idemKey = :idemKey")
    Optional<IdempotencyRecord> findByIdForUpdate(@Param("idemKey") String idemKey);
}
