package org.teamsparta.logisticsapi.global.exception;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public enum DomainExceptionCode {

    SHIPMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "배송 정보를 찾을 수 없습니다."),
    DUPLICATE_SHIPMENT(HttpStatus.CONFLICT, "동일한 주문에 대한 배송 요청이 이미 존재합니다."),
    INVALID_SHIPMENT_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "유효하지 않은 배송 상태 전이입니다."),

    EVENT_PUBLISH_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Kafka 이벤트 발행 중 오류가 발생했습니다."),
    EVENT_CONSUME_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Kafka 이벤트 처리 중 오류가 발생했습니다."),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다."),
    JSON_PROCESSING_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "JSON 처리 중 오류가 발생했습니다.");

    final HttpStatus status;
    final String message;
}
