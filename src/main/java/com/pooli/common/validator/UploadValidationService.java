package com.pooli.common.validator;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.dto.request.PresignedUrlReqDto;
import com.pooli.common.dto.request.UploadFileReqDto;

public interface UploadValidationService {
    void validateRequest(PresignedUrlReqDto request, AuthUserDetails userDetails);
    void validateFile(UploadFileReqDto file);
    String getFileExtension(String fileName);
}
