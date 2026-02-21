package org.teamsparta.inventoryapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryReservationRepository;
import org.teamsparta.inventoryapi.domain.inventory.scheduler.InventoryReservationExpireJob;
import org.teamsparta.inventoryapi.domain.inventory.service.InventoryExpireService;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class InventoryReservationExpireJobTest {

    @InjectMocks
    private InventoryReservationExpireJob inventoryReservationExpireJob;

    @Mock
    private InventoryReservationRepository inventoryReservationRepository;
    @Mock
    private InventoryExpireService inventoryExpireService;

    @Test
    @DisplayName("성공: 만료 대상을 조회하여 각각 expireOne을 호출")
    void tick_ProcessTargets(){
        // given
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        given(inventoryReservationRepository.lockExpiredReservations(any(), anyInt()))
                .willReturn(List.of(id1, id2));

        // when
        inventoryReservationExpireJob.tick();

        // then
        verify(inventoryExpireService, times(1)).expireOne(id1);
        verify(inventoryExpireService, times(1)).expireOne(id2);
    }

    @Test
    @DisplayName("예외 발생 - 개별 건 실패 시 로그를 남기고 다음 건을 계속 진행한다")
    void tick_ContinueEvenIfOneFails() {
        // given
        UUID failId = UUID.randomUUID();
        UUID successId = UUID.randomUUID();
        given(inventoryReservationRepository.lockExpiredReservations(any(), anyInt()))
                .willReturn(List.of(failId, successId));

        // 첫 번째 건은 예외 발생 
        doThrow(new RuntimeException("DB Error")).when(inventoryExpireService).expireOne(failId);

        // when
        inventoryReservationExpireJob.tick();

        // then
        verify(inventoryExpireService).expireOne(failId);
        verify(inventoryExpireService).expireOne(successId); // 실패해도 다음 건이 호출되어야 함
    }
}
