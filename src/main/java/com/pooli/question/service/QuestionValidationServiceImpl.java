package com.pooli.question.service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.common.exception.UploadErrorCode;
import com.pooli.question.domain.dto.request.QuestionCreateReqDto;
import org.springframework.stereotype.Component;

@Component
public class QuestionValidationServiceImpl implements QuestionValidationService {

    @Override
    public void validatePagingParams(Integer page, Integer size) {
        if (page == null || page < 0) {
            throw new ApplicationException(CommonErrorCode.INVALID_PAGE_NUMBER);
        }
        if (size == null || size <= 0) {
            throw new ApplicationException(CommonErrorCode.INVALID_PAGE_SIZE);
        }
    }

    @Override
    public int validateQuestionCreate(QuestionCreateReqDto req) {
        int attachmentCount = req.getAttachments() == null ? 0 : req.getAttachments().size();
        if (attachmentCount > 3) {
            throw new ApplicationException(UploadErrorCode.FILE_COUNT_EXCEEDED);
        }

        return attachmentCount;
    }
}