package org.teamsparta.orderapi.global.exception;

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

    FILE_UPLOAD(HttpStatus.INTERNAL_SERVER_ERROR, "파일 업로드 중 오류가 발생했습니다."),
    NOT_FOUND_ITEMS(HttpStatus.NOT_FOUND, "주문 항목을 찾을 수 없습니다."),
    NOT_FOUND_IDEMPOTENCY(HttpStatus.NOT_FOUND, "해당 멱등성 키로 진행 중인 내역을 찾을 수 없습니다."),


    NOT_FOUND_PRODUCT(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    DUPLICATE_SKU(HttpStatus.BAD_REQUEST, "sku가 이미 존재합니다."),
    CONCURRENT_DATA_CONFLICT(HttpStatus.CONFLICT, "데이터가 동시에 처리되고 있습니다. 잠시 후 데이터가 업데이트됩니다."),
    INVALID_SKU(HttpStatus.BAD_REQUEST,"존재하지 않는 상품 항목입니다."),
    PRODUCT_SNAPSHOT_NOT_READY(HttpStatus.NOT_FOUND, "주문 항목이 준비되지 않았습니다. 잠시 후에 다시 시도 해주세요."),
    INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "잘못된 수량 요청입니다."),
    PRODUCT_INACTIVE(HttpStatus.BAD_REQUEST, "주문 할 수 없는 상품입니다.")
    ;

    final HttpStatus status;
    final String message;
}