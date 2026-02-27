package com.pooli.question.service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.common.exception.UploadErrorCode;
import com.pooli.question.domain.dto.request.QuestionCreateReqDto;
import com.pooli.question.domain.entity.Question;
import com.pooli.question.exception.QuestionErrorCode;
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

    // 🔹 questionId 기본 검증
    @Override
    public void validateQuestionId(Long questionId) {
        if (questionId == null || questionId <= 0) {
            throw new ApplicationException(CommonErrorCode.INVALID_REQUEST_PARAM);
        }
    }

    // 🔹 질문 존재 여부 검증
    @Override
    public void validateQuestionExists(Question question) {
        if (question == null) {
            throw new ApplicationException(QuestionErrorCode.QUESTION_NOT_FOUND);
        }
    }

    @Override
    public void validateOwnerOrAdmin(Long resourceLineId,
                                     Long sessionLineId,
                                     Boolean isAdmin) {

        if (Boolean.TRUE.equals(isAdmin)) {
            return; // admin이면 통과
        }

        if (!resourceLineId.equals(sessionLineId)) {
            throw new ApplicationException(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN);
        }
    }
}