package com.pooli.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.auth.dto.request.LoginReqDto;
import com.pooli.auth.dto.response.LoginResDto;
import com.pooli.auth.service.AuthUserDetails;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

@Tag(name = "Authentication", description = "인증 및 인가 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;

    
    @Operation(
        summary = "유저 로그인",
        description = "이메일, 비밀번호를 받은 뒤 Cookie에 X-XSRF-TOKEN / JSESSIONID 전달"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "404", description = "앱 정보가 존재하지 않음"),
        @ApiResponse(responseCode = "500", description = "서버 오류"),
          
    })
    @PostMapping("/user/login")
    public ResponseEntity<Void> loginUser( @RequestBody LoginReqDto request,
    											  HttpServletRequest httpRequest,
    											  HttpServletResponse httpResponse) {
        return authenticateAndRespond(request, httpRequest, httpResponse, false);
    }

    
    @Operation(
            summary = "관리자 로그인",
            description = "이메일, 비밀번호를 받은 뒤 Cookie에 X-XSRF-TOKEN / JSESSIONID 전달"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "404", description = "앱 정보가 존재하지 않음"),
        @ApiResponse(responseCode = "500", description = "서버 오류"),
          
    })
    @PostMapping("/admin/login")
    public ResponseEntity<Void> loginAdmin( @RequestBody LoginReqDto request,
    											   HttpServletRequest httpRequest,
    											   HttpServletResponse httpResponse) {
        return authenticateAndRespond(request, httpRequest, httpResponse, true);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout( HttpServletRequest httpRequest,
    									HttpServletResponse httpResponse) {
    	
        SecurityContextHolder.clearContext();
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        clearCookie(httpResponse, "JSESSIONID");
        csrfTokenRepository.saveToken(null, httpRequest, httpResponse);
        clearCookie(httpResponse, "XSRF-TOKEN");

        return ResponseEntity.ok().build();
    }

    private ResponseEntity<Void> authenticateAndRespond( LoginReqDto request,
												         HttpServletRequest httpRequest,
												         HttpServletResponse httpResponse,
												         boolean adminOnly) {
    	
    	
        try {
        
        	Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
            );

            if (adminOnly && !hasRole(authentication, ADMIN_ROLE)) {
                SecurityContextHolder.clearContext();
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            AuthUserDetails principal = (AuthUserDetails) authentication.getPrincipal();
            AuthUserDetails sanitizedPrincipal = principal.withoutPassword();
            Authentication sanitizedAuthentication = new UsernamePasswordAuthenticationToken(
                sanitizedPrincipal,
                null,
                authentication.getAuthorities()
            );

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(sanitizedAuthentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, httpRequest, httpResponse);
            var csrfToken = csrfTokenRepository.generateToken(httpRequest);
            csrfTokenRepository.saveToken(csrfToken, httpRequest, httpResponse);

//            LoginResDto response = LoginResDto.builder()
//                .userId(sanitizedPrincipal.getUserId())
//                .userName(sanitizedPrincipal.getUsername())
//                .email(sanitizedPrincipal.getEmail())
//                .lineId(sanitizedPrincipal.getLineId())
//                .roles(sanitizedPrincipal.getRoleNames())
//                .build();

            return ResponseEntity.ok().build();
        } catch (AuthenticationException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
            .anyMatch(authority -> role.equals(authority.getAuthority()));
    }

    private void clearCookie(HttpServletResponse response, String name) {
        if (!StringUtils.hasText(name)) {
            return;
        }
        Cookie cookie = new Cookie(name, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }
}
