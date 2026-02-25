package com.pooli.common.validator;

import com.pooli.common.dto.request.PresignedUrlReqDto;
import com.pooli.common.dto.request.UploadFileReqDto;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.UploadErrorCode;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class UploadValidationServiceImpl implements UploadValidationService{
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private static final Set<String> ALLOWED_DOMAINS = Set.of(
            "QUESTION",
            "ANSWER"
    );

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    @Override
    public void validateRequest(PresignedUrlReqDto request) {

        if (request == null || request.getFiles() == null || request.getFiles().isEmpty()) {
            throw new ApplicationException(UploadErrorCode.INVALID_UPLOAD_REQUEST);
        }

        if (request.getFiles().size() > 3) {
            throw new ApplicationException(UploadErrorCode.FILE_COUNT_EXCEEDED);
        }

        // 도메인이 null 인지 확인
        if (request.getDomain() == null || request.getDomain().isBlank()) {
            throw new ApplicationException(UploadErrorCode.INVALID_DOMAIN);
        }

        // domain 문자열 대문자 변환 후 허용값 체크
        request.setDomain(request.getDomain().toUpperCase());
        if (!ALLOWED_DOMAINS.contains(request.getDomain())) {
            throw new ApplicationException(UploadErrorCode.INVALID_DOMAIN);
        }

        // 각 파일 검증
        request.getFiles().forEach(this::validateFile);
    }

    @Override
    public void validateFile(UploadFileReqDto file) {

        if (file.getFileName() == null || file.getFileName().isBlank()) {
            throw new ApplicationException(UploadErrorCode.INVALID_UPLOAD_REQUEST);
        }

        if (file.getContentType() == null ||
                !ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new ApplicationException(UploadErrorCode.INVALID_UPLOAD_REQUEST);
        }

        // 확장자 체크
        String extension = getFileExtension(file.getFileName());
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
            throw new ApplicationException(UploadErrorCode.INVALID_FILE);
        }
    }

    /** 파일명에서 확장자 추출 */
    @Override
    public String getFileExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        if (idx == -1 || idx == fileName.length() - 1) return null;
        return fileName.substring(idx + 1);
    }
}
