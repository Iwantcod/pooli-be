package com.pooli.auth.controller;

import com.pooli.auth.dto.request.LoginReqDto;
import com.pooli.auth.dto.response.LoginResDto;
import com.pooli.auth.service.AuthUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    @PostMapping("/user/login")
    public ResponseEntity<LoginResDto> loginUser(
        @RequestBody LoginReqDto request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        return authenticateAndRespond(request, httpRequest, httpResponse, false);
    }

    @PostMapping("/admin/login")
    public ResponseEntity<LoginResDto> loginAdmin(
        @RequestBody LoginReqDto request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        return authenticateAndRespond(request, httpRequest, httpResponse, true);
    }

    private ResponseEntity<LoginResDto> authenticateAndRespond(
        LoginReqDto request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse,
        boolean adminOnly
    ) {
    	
    	
        try {
        
        	Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
            );

            if (adminOnly && !hasRole(authentication, ADMIN_ROLE)) {
                SecurityContextHolder.clearContext();
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, httpRequest, httpResponse);

            AuthUserDetails principal = (AuthUserDetails) authentication.getPrincipal();
            LoginResDto response = LoginResDto.builder()
                .userId(principal.getUserId())
                .userName(principal.getUsername())
                .email(principal.getEmail())
                .roles(principal.getRoleNames())
                .build();

            return ResponseEntity.ok(response);
        } catch (AuthenticationException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
            .anyMatch(authority -> role.equals(authority.getAuthority()));
    }
}
