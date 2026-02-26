package com.pooli.common.exception;

import org.springframework.http.HttpStatus;

public enum PolicyErrorCode implements ErrorCode {

	/* 400(Bad Request) */
	
    // 차단 종료 시간이 시작 시간보다 느리게 설정 
	INVALID_BLOCK_TIME_RANGE(
            HttpStatus.BAD_REQUEST,
            "POLICY:4000",
            "차단 시작 시간은 종료 시간보다 빨라야 합니다."
    ),

    // 차단/종료 시간 누락
	MISSING_BLOCK_TIME(
            HttpStatus.BAD_REQUEST,
            "POLICY:4001",
            "차단 시작 시간이나 종료 시간이 누락되었습니다."
    ),

    // 차단 기간이 24시간 이상으로 설정
	BLOCK_TIME_EXCEEDED(
            HttpStatus.BAD_REQUEST,
            "POLICY:4002",
            "차단 기간은 24시간 미만으로 설정 가능합니다."
    ),

    // 데이터 사용량 제한 범위 초과
	DATA_LIMIT_EXCEEDED(
            HttpStatus.BAD_REQUEST,
            "POLICY:4003",
            "제한할 수 있는 데이터의 범위를 초과했습니다."
    ),

    
    /* 404(Not Found)*/
	
    // 회선 데이터 정보 없음
	LINE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "POLICY:4400",
            "해당 회선이 존재하지 않습니다."
    ),
    
    // 반복적 차단 정보 없음
	REPEAT_BLOCK_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "POLICY:4401",
            "해당 반복적 차단 정보가 존재하지 않습니다."
    ),
    
    // 앱 정책 정보 없음
	APP_POLICY_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "POLICY:4402",
            "해당 앱 정책 정보가 존재하지 않습니다."
    ),
    
    // 앱 정보 없음
	APP_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "POLICY:4403",
            "해당 앱 정보가 존재하지 않습니다."
    ),
    
    // 대상 가족 그룹 정보 없음
	FAMILY_GROUP_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "POLICY:4404",
            "대상 가족 그룹이 존재하지 않습니다."
    ),
    
    // 관리자용 정책 정보 없음
    ADMIN_POLICY_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "POLICY:4405",
            "해당 정책 정보가 존재하지 않습니다."
    ),
    
    
    /* 409(conflict) */
    
    APP_EXCEPTION_CONFLICT(
            HttpStatus.CONFLICT,
            "POLICY:4900",
            "이미 정책 예외가 허용된 어플리케이션입니다."
    ),
    
    POLICY_DELETED_CONFLICT(
            HttpStatus.CONFLICT,
            "POLICY:4901",
            "이미 삭제된 정책입니다."
    ),
    
    POLICY_ACTIVE_CONFLICT(
            HttpStatus.CONFLICT,
            "POLICY:4902",
            "이미 활성화된 정책입니다."
    ),
    
    POLICY_INACTIVE_CONFLICT(
            HttpStatus.CONFLICT,
            "POLICY:4903",
            "활성화되지 않은 정책입니다."
    ),
    
    BLOCK_POLICY_CONFLICT(
            HttpStatus.CONFLICT,
            "POLICY:4904",
            "기존의 차단 정책과 충돌합니다."
    );
    

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    PolicyErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override public HttpStatus getHttpStatus() { return httpStatus; }
    @Override public String getCode() { return code; }
    @Override public String getMessage() { return message; }
}