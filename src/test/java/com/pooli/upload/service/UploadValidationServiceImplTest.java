package com.pooli.upload.service;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.dto.request.PresignedUrlReqDto;
import com.pooli.common.dto.request.UploadFileReqDto;
import com.pooli.common.enums.FileDomain;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.validator.UploadValidationServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UploadValidationServiceImplTest {

    private final UploadValidationServiceImpl validationService =
            new UploadValidationServiceImpl();

    private AuthUserDetails mockUser(String role) {
        return AuthUserDetails.builder()
                .userId(1L)
                .userName("tester")
                .email("test@test.com")
                .password("pw")
                .lineId(1L)
                .authorities(AuthUserDetails.toAuthorities(List.of(role)))
                .build();
    }

    private UploadFileReqDto validFile() {
        UploadFileReqDto file = new UploadFileReqDto();
        file.setFileName("image.png");
        file.setContentType("image/png");
        return file;
    }

    @Test
    @DisplayName("파일 3개 초과 시 일반 사용자 예외")
    void fileCountExceededForUser() {
        PresignedUrlReqDto req = new PresignedUrlReqDto();
        req.setDomain(FileDomain.QUESTION);
        req.setFiles(List.of(validFile(), validFile(), validFile(), validFile()));

        AuthUserDetails user = mockUser("ROLE_USER");

        assertThrows(ApplicationException.class,
                () -> validationService.validateRequest(req, user));
    }

    @Test
    @DisplayName("관리자는 3개 초과 가능")
    void adminCanUploadMoreThanThree() {
        PresignedUrlReqDto req = new PresignedUrlReqDto();
        req.setDomain(FileDomain.QUESTION);
        req.setFiles(List.of(validFile(), validFile(), validFile(), validFile()));

        AuthUserDetails admin = mockUser("ROLE_ADMIN");

        assertDoesNotThrow(() ->
                validationService.validateRequest(req, admin));
    }

    @Test
    @DisplayName("허용되지 않은 contentType 예외")
    void invalidContentType() {
        UploadFileReqDto file = new UploadFileReqDto();
        file.setFileName("image.png");
        file.setContentType("application/pdf");

        PresignedUrlReqDto req = new PresignedUrlReqDto();
        req.setDomain(FileDomain.QUESTION);
        req.setFiles(List.of(file));

        AuthUserDetails user = mockUser("ROLE_USER");

        assertThrows(ApplicationException.class,
                () -> validationService.validateRequest(req, user));
    }

    @Test
    @DisplayName("확장자 없는 파일 예외")
    void invalidExtension() {
        UploadFileReqDto file = new UploadFileReqDto();
        file.setFileName("image");
        file.setContentType("image/png");

        assertThrows(ApplicationException.class,
                () -> validationService.validateFile(file));
    }
}