package com.pooli.question.service;

import com.pooli.question.domain.dto.request.QuestionCreateReqDto;
import com.pooli.question.domain.entity.Question;

public interface QuestionValidationService {
    void validatePagingParams(Integer page, Integer size);
    int validateQuestionCreate(QuestionCreateReqDto req);
    void validateQuestionId(Long questionId);
    void validateQuestionExists(Question question);
}
