package org.teamsparta.orderapi.domain.order.cache;

import java.util.Objects;

/**
 * 캐시 조회 결과. MISS(캐시에 없음)와 ERROR(Redis 장애)를 구분해,
 * 호출하는 쪽이 장애 중에는 캐시 저장을 건너뛸 수 있게 한다.
 */
public record CacheResult<T>(Type type, T value) {

    public enum Type { HIT, MISS, ERROR }

    public static <T> CacheResult<T> hit(T value) {
        return new CacheResult<>(Type.HIT, Objects.requireNonNull(value));
    }

    public static <T> CacheResult<T> miss() {
        return new CacheResult<>(Type.MISS, null);
    }

    public static <T> CacheResult<T> error() {
        return new CacheResult<>(Type.ERROR, null);
    }

    public boolean isHit() { return type == Type.HIT; }
    public boolean isMiss() { return type == Type.MISS; }
    public boolean isError() { return type == Type.ERROR; }
}