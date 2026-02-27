package com.pooli.question.service;

import com.pooli.question.domain.dto.request.QuestionCreateReqDto;

public interface QuestionValidationService {
    void validatePagingParams(Integer page, Integer size);
    int validateQuestionCreate(QuestionCreateReqDto req);
}
