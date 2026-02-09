package org.teamsparta.inventoryapi.global.exception;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.teamsparta.inventoryapi.global.response.ApiResponse;

import java.util.stream.Collectors;

@Slf4j
@Hidden
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String SERVER_ERROR = "SERVER_ERROR";

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponse<Void>> handleDomainException(DomainException ex){
        log.warn("[DomainException] : code={}, message={}", ex.getCode(), ex.getMessage());
        return ApiResponse.fail(ex.getHttpStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException ex) {
        String errorMessage = extractErrorMessages(ex);
        log.warn("[ValidationException] : {}", errorMessage);
        return ApiResponse.fail(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, errorMessage);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException ex) {
        String errorMessage = extractErrorMessages(ex);
        log.warn("[BindException] : {}", errorMessage);
        return ApiResponse.fail(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, errorMessage);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        log.error("[Exception] : ", ex);
        return ApiResponse.fail(HttpStatus.INTERNAL_SERVER_ERROR, SERVER_ERROR, ex.getMessage());
    }

    private String extractErrorMessages(BindException ex) {
        return ex.getBindingResult()
                .getAllErrors()
                .stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining(", "));
    }
}
