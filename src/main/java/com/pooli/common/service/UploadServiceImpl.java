package com.pooli.common.service;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.enums.FileDomain;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.dto.request.PresignedUrlReqDto;
import com.pooli.common.dto.request.UploadFileReqDto;
import com.pooli.common.dto.response.PresignedUrlResDto;
import com.pooli.common.dto.response.UploadFileResDto;
import com.pooli.common.exception.UploadErrorCode;
import com.pooli.common.validator.UploadValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UploadServiceImpl implements UploadService {

    private final S3Presigner presigner;
    private final UploadValidationService uploadValidationService;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    @Override
    public PresignedUrlResDto generatePresignedUrls(PresignedUrlReqDto request, AuthUserDetails userDetails) {

        uploadValidationService.validateRequest(request, userDetails);

        try {
            List<UploadFileResDto> uploads = request.getFiles()
                    .stream()
                    .map(file -> createPresignedUrl(file, request.getDomain()))
                    .collect(Collectors.toList());

            return PresignedUrlResDto.builder()
                    .uploads(uploads)
                    .build();

        } catch (Exception e) {
            throw new ApplicationException(
                    UploadErrorCode.PRESIGNED_URL_GENERATION_FAILED,
                    e
            );
        }
    }

    @Override
    public String generateGetPresignedUrl(String key) {

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        GetObjectPresignRequest presignRequest =
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(10))
                        .getObjectRequest(getObjectRequest)
                        .build();

        PresignedGetObjectRequest presignedRequest =
                presigner.presignGetObject(presignRequest);

        return presignedRequest.url().toString();
    }

    @Override
    public UploadFileResDto createPresignedUrl(UploadFileReqDto file, FileDomain domain) {

        String key = generateKey(file, domain);

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(file.getContentType())
                .build();

        PutObjectPresignRequest presignRequest =
                PutObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(10))
                        .putObjectRequest(putObjectRequest)
                        .build();

        PresignedPutObjectRequest presignedRequest =
                presigner.presignPutObject(presignRequest);

        String uploadUrl = presignedRequest.url().toString();

        return UploadFileResDto.builder()
                .uploadUrl(uploadUrl)
                .s3Key(key)
                .build();
    }

    @Override
    public String generateKey(UploadFileReqDto file, FileDomain domain) {
        String ext = uploadValidationService
                .getFileExtension(file.getFileName())
                .toLowerCase(Locale.ROOT);

        String prefix = domain.name().toLowerCase(Locale.ROOT);

        String uuid = UUID.randomUUID()
                .toString()
                .replace("-", "");

        return prefix + "/" + uuid + "." + ext;
    }

}