package org.teamsparta.productapi.global.exception;

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
    NOT_FOUND_CATEGORY(HttpStatus.NOT_FOUND, "카테고리를 찾을 수 없습니다."),
    CATEGORY_HAS_CHILDREN(HttpStatus.BAD_REQUEST, "하위 카테고리가 존재하여 삭제할 수 없습니다."),
    DUPLICATE_PARENT(HttpStatus.BAD_REQUEST, "자기 자신을 부모카테고리로 설정할 수 없습니다,"),

    NOT_FOUND_PRODUCT(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    DUPLICATE_SKU(HttpStatus.BAD_REQUEST, "sku가 이미 존재합니다."),

    EVENT_PUBLISH_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "kafka 이벤트 발행과정에서 오류가 발생했습니다.")

    ;

    final HttpStatus status;
    final String message;
}