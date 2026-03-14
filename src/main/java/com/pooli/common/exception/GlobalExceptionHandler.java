package com.pooli.common.exception;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.pooli.common.dto.ErrorResDto;
import com.pooli.common.dto.Violation;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final String TRACE_ID_KEY = "traceId";
    private static final int MAX_LOG_BODY_LENGTH = 1000;

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ErrorResDto> handleApplicationException(ApplicationException ex, HttpServletRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        String body = truncate((String) request.getAttribute("cachedRequestBody"), MAX_LOG_BODY_LENGTH);

        log.error(
                "application_error traceId={} uri={} errorCode={} message={}",
                MDC.get(TRACE_ID_KEY),
                request.getRequestURI(),
                errorCode.getCode(),
                ex.getMessage(),
                body
        );


        ErrorResDto bodyDto = ErrorResDto.builder()
                .code(errorCode.getCode())
                .message(ex.getMessage())
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get(TRACE_ID_KEY))
                .build();

        return ResponseEntity.status(errorCode.getHttpStatus()).body(bodyDto);
    }

    // COMMON-4000: 요청 형식 불일치 (JSON 파싱/역직렬화 실패)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResDto> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        ErrorResDto body = ErrorResDto.builder()
                .code("COMMON:4000")
                .message("요청 형식이 일치하지 않습니다.")
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get(TRACE_ID_KEY))
                .violations(List.of(
                        Violation.builder()
                                .target("body")
                                .name("requestBody")
                                .reason("요청 본문을 해석할 수 없습니다.")
                                .build()
                ))
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // COMMON-4001: 요청 DTO 필드 유효성 검증 실패 (@Valid @RequestBody)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResDto> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {

        List<Violation> violations = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toBodyViolation)
                .toList();

        ErrorResDto body = ErrorResDto.builder()
                .code("COMMON:4001")
                .message("요청 DTO 필드 유효성 검증에 실패했습니다.")
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get(TRACE_ID_KEY))
                .violations(violations)
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // @ModelAttribute 바인딩/검증 실패도 동일하게 COMMON-4001로 수렴
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResDto> handleBindException(BindException ex) {

        List<Violation> violations = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toBodyViolation)
                .toList();

        ErrorResDto body = ErrorResDto.builder()
                .code("COMMON:4001")
                .message("요청 DTO 필드 유효성 검증에 실패했습니다.")
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get(TRACE_ID_KEY))
                .violations(violations)
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // COMMON-4002: RequestParam 유효성 검증 실패 (@Validated + @RequestParam/@PathVariable)
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResDto> handleConstraintViolationException(ConstraintViolationException ex) {

        // propertyPath가 "method.arg0"처럼 길어질 수 있습니다.
        // 필요하면 name을 후처리(마지막 토큰만)하는 로직을 추가하십시오.
        List<Violation> violations = ex.getConstraintViolations()
                .stream()
                .map(v -> Violation.builder()
                        .target("param")
                        .name(v.getPropertyPath().toString())
                        .reason(v.getMessage())
                        .build())
                .toList();

        ErrorResDto body = ErrorResDto.builder()
                .code("COMMON:4002")
                .message("요청 파라미터 유효성 검증에 실패했습니다.")
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get(TRACE_ID_KEY))
                .violations(violations)
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // COMMON-4003: RequestParam 타입 불일치
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResDto> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException ex) {

        ErrorResDto body = ErrorResDto.builder()
                .code("COMMON:4003")
                .message("요청 파라미터 타입이 일치하지 않습니다.")
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get(TRACE_ID_KEY))
                .violations(List.of(
                        Violation.builder()
                                .target("param")
                                .name(ex.getName()) // 파라미터명
                                .reason("요청 파라미터 타입이 일치하지 않습니다.")
                                .build()
                ))
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // COMMON-4004: 필수 RequestParam 누락
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResDto> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException ex
    ) {
        ErrorResDto body = ErrorResDto.builder()
                .code("COMMON:4004")
                .message("필수 요청 파라미터가 누락되었습니다.")
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get(TRACE_ID_KEY))
                .violations(List.of(
                        Violation.builder()
                                .target("param")
                                .name(ex.getParameterName())
                                .reason("필수 요청 파라미터가 누락되었습니다.")
                                .build()
                ))
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // COMMON-4005: 지원하지 않는 HTTP 메서드
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResDto> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException ex
    ) {
        ErrorResDto body = ErrorResDto.builder()
                .code("COMMON:4005")
                .message("지원하지 않는 HTTP 메서드입니다.")
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get(TRACE_ID_KEY))
                .violations(List.of(
                        Violation.builder()
                                .target("request")
                                .name("method")
                                .reason("지원하지 않는 HTTP 메서드입니다.")
                                .build()
                ))
                .build();

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    // COMMON-4006: Content-Type 불일치
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResDto> handleHttpMediaTypeNotSupportedException(
            HttpMediaTypeNotSupportedException ex
    ) {
        ErrorResDto body = ErrorResDto.builder()
                .code("COMMON:4006")
                .message("지원하지 않는 Content-Type입니다.")
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get(TRACE_ID_KEY))
                .violations(List.of(
                        Violation.builder()
                                .target("request")
                                .name("contentType")
                                .reason("지원하지 않는 Content-Type입니다.")
                                .build()
                ))
                .build();

        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(body);
    }

    // COMMON-5001: 데이터베이스 오류
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResDto> handleDataAccessException(DataAccessException ex) {
        log.error(
                "database_error traceId={}",
                MDC.get(TRACE_ID_KEY),
                ex
        );

        ErrorResDto body = ErrorResDto.builder()
                .code("COMMON:5001")
                .message("데이터베이스 처리 중 오류가 발생했습니다.")
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get(TRACE_ID_KEY))
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // COMMON-5002: 외부 시스템 오류(예: RestTemplate/WebClient/Feign 계열을 여기에 묶기)
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ErrorResDto> handleRestClientException(RestClientException ex) {
        log.error(
                "external_system_error traceId={}",
                MDC.get(TRACE_ID_KEY),
                ex
        );

        ErrorResDto body = ErrorResDto.builder()
                .code("COMMON:5002")
                .message("외부 시스템 호출 중 오류가 발생했습니다.")
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get(TRACE_ID_KEY))
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // COMMON-5003: 동시성/트랜잭션 처리 오류(최소 세트에서는 트랜잭션도 여기에 포함 가능)
    @ExceptionHandler({TransactionSystemException.class, UnexpectedRollbackException.class})
    public ResponseEntity<ErrorResDto> handleTransactionException(RuntimeException ex) {
        log.error("Transaction error", ex);

        ErrorResDto body = ErrorResDto.builder()
                .code("COMMON:5003")
                .message("요청 처리 중 오류가 발생했습니다.")
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get(TRACE_ID_KEY))
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // COMMON-4302: @PreAuthorize 인가 실패
//    @ExceptionHandler(AccessDeniedException.class)
//    public ResponseEntity<ErrorResDto> handleAccessDeniedException(AccessDeniedException ex) {
//        ErrorResDto body = ErrorResDto.builder()
//                .code(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN.getCode())
//                .message(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN.getMessage())
//                .timestamp(OffsetDateTime.now().toString())
//                .traceId(MDC.get(TRACE_ID_KEY))
//                .build();
//
//        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
//    }

    // COMMON-5000: 최종 fallback
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResDto> handleException(Exception ex) {
        log.error(
                "unhandled_error traceId={}",
                MDC.get(TRACE_ID_KEY),
                ex
        );
        
        if (ex instanceof AccessDeniedException) {
            throw (org.springframework.security.access.AccessDeniedException) ex;
        }

        ErrorResDto body = ErrorResDto.builder()
                .code("COMMON:5000")
                .message("서버 내부 오류가 발생했습니다.")
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get(TRACE_ID_KEY))
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // FieldError(Http Body Spring Validator 발생 예외) 공통 변환 유틸
    private Violation toBodyViolation(FieldError error) {
        return Violation.builder()
                .target("body")
                .name(error.getField())
                .reason(error.getDefaultMessage())
                .build();
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...(truncated)";
    }
}