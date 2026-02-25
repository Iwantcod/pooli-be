package com.pooli.common.service;

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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UploadServiceImpl implements UploadService {

    private final S3Presigner presigner;
    private final UploadValidationService uploadValidationService;

    @Value("${cloud.aws.region}")
    private String region;


    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    @Override
    public PresignedUrlResDto generatePresignedUrls(PresignedUrlReqDto request) {

        uploadValidationService.validateRequest(request);
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
    public UploadFileResDto createPresignedUrl(UploadFileReqDto file, String domain) {

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
        String fileUrl = String.format(
                "https://%s.s3.%s.amazonaws.com/%s",
                bucket, region, key
        );

        return UploadFileResDto.builder()
                .uploadUrl(uploadUrl)
                .fileUrl(fileUrl)
                .build();
    }

    @Override
    public String generateKey(UploadFileReqDto file, String domain) {
        String uuid = UUID.randomUUID().toString();
        return domain.toLowerCase() + "/" + uuid + "-" + file.getFileName();
    }

}