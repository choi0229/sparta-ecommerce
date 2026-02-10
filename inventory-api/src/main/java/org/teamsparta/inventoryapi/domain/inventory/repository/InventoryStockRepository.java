package org.teamsparta.inventoryapi.domain.inventory.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryStock;

import java.util.List;

public interface InventoryStockRepository extends JpaRepository<InventoryStock, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InventoryStock s where s.sku in :skus")
    List<InventoryStock> findAllSkuInForUpdate(@Param("skus") List<String> skus);


}
