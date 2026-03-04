package com.pooli.auth.exception;

import java.io.IOException;
import java.time.OffsetDateTime;

import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.common.dto.ErrorResDto;
import com.pooli.common.exception.CommonErrorCode;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint{
	
	private static final String TRACE_ID_KEY = "traceId";
    private final ObjectMapper objectMapper;	
	
	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException, ServletException {
		
		ErrorResDto body = ErrorResDto.builder()
                .code(CommonErrorCode.UNAUTHORIZED.getCode())
                .message(CommonErrorCode.UNAUTHORIZED.getMessage())
                .timestamp(OffsetDateTime.now().toString())
                .traceId(MDC.get(TRACE_ID_KEY))
                .build();

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
		
	}

}
