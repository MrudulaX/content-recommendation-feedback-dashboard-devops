package com.crfd.dashboard.web;

import com.crfd.dashboard.service.DuplicateEventException;
import com.crfd.dashboard.service.NotFoundException;
import com.crfd.dashboard.web.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/** Translates exceptions into the uniform {@link ApiError} contract. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(DuplicateEventException.class)
    public ResponseEntity<ApiError> handleDuplicate(DuplicateEventException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Request validation failed", request, fields);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleMalformed(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed request: " + ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        String correlationId = java.util.UUID.randomUUID().toString();
        log.error("Unhandled exception on {} {} [correlationId={}]",
                request.getMethod(), request.getRequestURI(), correlationId, ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected error. Correlation id: " + correlationId, request, List.of());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request,
                                           List<ApiError.FieldError> fields) {
        ApiError body = new ApiError(java.time.Instant.now(), status.value(), status.getReasonPhrase(),
                message, request.getRequestURI(), fields);
        return ResponseEntity.status(status).body(body);
    }
}
