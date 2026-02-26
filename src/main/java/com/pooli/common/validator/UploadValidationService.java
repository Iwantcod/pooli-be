package com.pooli.common.validator;

import com.pooli.common.dto.request.PresignedUrlReqDto;
import com.pooli.common.dto.request.UploadFileReqDto;

public interface UploadValidationService {
    void validateRequest(PresignedUrlReqDto request);
    void validateFile(UploadFileReqDto file);
    String getFileExtension(String fileName);
}
