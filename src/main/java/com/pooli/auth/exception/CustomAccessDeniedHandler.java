package com.pooli.auth.exception;

import java.io.IOException;
import java.time.OffsetDateTime;

import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.auth.exception.denied.AdminOnlyDeniedException;
import com.pooli.auth.exception.denied.OwnerOnlyDeniedException;
import com.pooli.auth.exception.denied.UserOnlyDeniedException;
import com.pooli.common.dto.ErrorResDto;
import com.pooli.common.exception.CommonErrorCode;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CustomAccessDeniedHandler implements AccessDeniedHandler {
	
	private static final String TRACE_ID_KEY = "traceId";
    private final ObjectMapper objectMapper;

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException ex) throws IOException, ServletException {
		
		CommonErrorCode errorCode = resolveErrorCode(ex);
		
		ErrorResDto body = ErrorResDto.builder()
	              .code(errorCode.getCode())
	              .message(errorCode.getMessage())
	              .timestamp(OffsetDateTime.now().toString())
	              .traceId(MDC.get(TRACE_ID_KEY))
	              .build();
		
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
	}
	
	
	
	private CommonErrorCode resolveErrorCode(AccessDeniedException ex) {
        if (ex instanceof AdminOnlyDeniedException) {
            return CommonErrorCode.ADMIN_FORBIDDEN; // COMMON:4301 - 관리자 권한 없음
        }
        if (ex instanceof OwnerOnlyDeniedException ) {
            return CommonErrorCode.FAMILY_REPRESENTATIVE_FORBIDDEN; // COMMON:4300 가족 대표자 권한 없음 
        }
        if (ex instanceof UserOnlyDeniedException) {
            return CommonErrorCode.USER_FORBIDDEN; // COMMON:4303 유저 권한이 없음
        }
        return CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN; // COMMON:4302 - 접근 권한 없음
    }

}
