package com.pooli.upload.service;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.dto.request.PresignedUrlReqDto;
import com.pooli.common.dto.request.UploadFileReqDto;
import com.pooli.common.dto.response.PresignedUrlResDto;
import com.pooli.common.enums.FileDomain;
import com.pooli.common.service.UploadServiceImpl;
import com.pooli.common.validator.UploadValidationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadServiceImplTest {

    @Mock
    private S3Presigner presigner;

    @Mock
    private UploadValidationService uploadValidationService;

    @InjectMocks
    private UploadServiceImpl uploadService;

    @Test
    @DisplayName("Presigned URL 생성 성공")
    void generatePresignedUrls_success() throws Exception {

        AuthUserDetails userDetails = AuthUserDetails.builder()
                .userId(1L)
                .userName("test")
                .email("test@test.com")
                .password("pw")
                .authorities(List.of())
                .build();

        // given
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);

        when(uploadValidationService.getFileExtension(anyString()))
                .thenReturn("jpg");

        when(presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenReturn(presigned);

        when(presigned.url())
                .thenReturn(new URL("https://test-url.com"));

        PresignedUrlReqDto request = new PresignedUrlReqDto();

        UploadFileReqDto file = new UploadFileReqDto();
        file.setFileName("image.jpg");
        file.setContentType("image/jpeg");

        request.setFiles(List.of(file));
        request.setDomain(FileDomain.QUESTION);

        // when
        PresignedUrlResDto result =
                uploadService.generatePresignedUrls(request, userDetails);

        // then
        assertAll(
                () -> assertNotNull(result),
                () -> assertNotNull(result.getUploads()),
                () -> assertEquals(1, result.getUploads().size()),
                () -> assertEquals(
                        "https://test-url.com",
                        result.getUploads().get(0).getUploadUrl()
                )
        );
    }
}