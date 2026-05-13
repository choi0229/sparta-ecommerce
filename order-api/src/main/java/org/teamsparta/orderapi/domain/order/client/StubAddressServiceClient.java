package org.teamsparta.orderapi.domain.order.client;

import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.util.Map;

/**
 * address-api 미구축 시 사용하는 인메모리 Stub.
 * 실제 address-api가 준비되면 HTTP 구현체로 교체하고 이 클래스를 제거한다.
 */
public class StubAddressServiceClient implements AddressServiceClient {

    private static final Map<Long, AddressInfo> STORE = Map.of(
            1L, new AddressInfo("홍길동", "서울시 강남구 테헤란로 1"),
            2L, new AddressInfo("김철수", "부산시 해운대구 달맞이길 2"),
            3L, new AddressInfo("이영희", "대구시 중구 동성로 3")
    );

    @Override
    public AddressInfo findById(Long addressId, Long userId) {
        AddressInfo info = STORE.get(addressId);
        if (info == null) {
            throw new DomainException(DomainExceptionCode.ADDRESS_NOT_FOUND);
        }
        return info;
    }
}
