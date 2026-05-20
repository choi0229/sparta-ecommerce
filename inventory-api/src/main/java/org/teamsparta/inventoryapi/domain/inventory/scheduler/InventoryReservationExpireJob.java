package org.teamsparta.inventoryapi.domain.inventory.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryReservationRepository;
import org.teamsparta.inventoryapi.domain.inventory.service.InventoryExpireService;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryReservationExpireJob {
    private final InventoryReservationRepository inventoryReservationRepository;
    private final InventoryExpireService inventoryExpireService;

    @Scheduled(fixedDelay = 1000)
    public void tick(){
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());

        List<UUID> targets = inventoryReservationRepository.lockExpiredReservations(now, 50);
        if(targets.isEmpty()){
            return;
        }

        for(UUID target : targets){
            try{
                inventoryExpireService.expireOne(target);
            } catch (Exception e) {
                log.error("Expire failed. reservationId={}", target, e);
            }
        }
    }
}
