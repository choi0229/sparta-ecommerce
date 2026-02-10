package org.teamsparta.inventoryapi.global.exception;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public enum DomainExceptionCode {

    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "잘못된 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),
    MISSING_TOKEN(HttpStatus.UNAUTHORIZED, "토큰이 누락되었습니다."),
    UNAUTHORIZED_ACCESS(HttpStatus.UNAUTHORIZED, "인증되지 않은 접근입니다."),
    JSON_PROCESSING_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Json 데이터 처리 중 에러가 발생하였습니다."),


    DUPLICATE_SKU(HttpStatus.BAD_REQUEST, "sku가 이미 존재합니다."),


    EVENT_PUBLISH_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "kafka 이벤트 발행과정에서 오류가 발생했습니다."),
    EVENT_CONSUME_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "kafka 이벤트 과정에서 오류가 발생했습니다."),
    RESERVATION_ALREADY_TERMINATED(HttpStatus.BAD_REQUEST, "예약은 이미 종료되었습니다."),
    INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "유효하지 않은 수량입니다."),
    INVALID_SKU(HttpStatus.BAD_REQUEST, "유효하지 않은 sku입니다."),
    STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "재고를 찾지 못했습니다."),
    OUT_OF_STOCK(HttpStatus.BAD_REQUEST, "재고가 부족합니다.")
    ;

    final HttpStatus status;
    final String message;
}