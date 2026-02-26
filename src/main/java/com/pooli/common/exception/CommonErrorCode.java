package com.pooli.common.exception;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

	/* 400 Bad Request */

	// 요청 형식 오류
	INVALID_REQUEST_FORMAT(
            HttpStatus.BAD_REQUEST,
            "COMMON:4000",
            "요청 형식 불일치"
    ),

    // 요청 DTO 필드 유효성 검증 실패
    INVALID_REQUEST_BODY(
            HttpStatus.BAD_REQUEST,
            "COMMON:4001",
            "요청 DTO 필드 유효성 검증 실패"
    ),

    // RequestParam 유효성 검증 실패
	INVALID_REQUEST_PARAM(
            HttpStatus.BAD_REQUEST,
            "COMMON:4002",
            "RequestParam 유효성 검증 실패"
    ),

    // RequestParam 타입 불일치
	MISMATCH_REQUEST_PARAM_TYPE(
            HttpStatus.BAD_REQUEST,
            "COMMON:4003",
            "RequestParam 타입 불일치"
    ),

    // RequestParam 누락
	MISSING_REQUEST_PARAM(
            HttpStatus.BAD_REQUEST,
            "COMMON:4004",
            "필수 RequestParam 누락"
    ),

    // 지원하지 않는 HTTP 메서드
	NOT_SUPPORTED_METHOD(
            HttpStatus.BAD_REQUEST,
            "COMMON:4005",
            "지원하지 않는 HTTP 메서드"
    ),

    // Content-Type 불일치
	UNSUPPORTED_CONTENT_TYPE(
            HttpStatus.BAD_REQUEST,
            "COMMON:4006",
            "Content-Type 불일치"
    ),


	/* 403 Forbidden */

	// 가족 대표자 권한 없음
	FAMILY_REPRESENTATIVE_FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "COMMON:4300",
            "가족 대표자 권한이 없습니다."
    ),

    // 관리자 권한 없음
	ADMIN_FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "COMMON:4301",
            "관리자 권한이 없습니다."
    ),


	/* 500 Internal Server Error */

    // 서버 내 오류 발생
	INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "COMMON:5000",
            "서버 내부 오류가 발생했습니다."
    ),

    // DB 오류 발생
	DATABASE_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "COMMON:5001",
            "데이터베이스 처리 중 오류가 발생했습니다."
    ),

    EXTERNAL_SYSTEM_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "COMMON:5002",
            "외부 시스템 호출 중 오류가 발생했습니다."
    ),

    TRANSACTION_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "COMMON:5003",
            "요청 처리 중 오류가 발생했습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    @Override public HttpStatus getHttpStatus() { return httpStatus; }
    @Override public String getCode() { return code; }
    @Override public String getMessage() { return message; }
}