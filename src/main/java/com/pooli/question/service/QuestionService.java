package com.pooli.question.service;

import com.pooli.question.domain.dto.request.QuestionCreateReqDto;
import com.pooli.question.domain.dto.response.QuestionCategoryListResDto;
import com.pooli.question.domain.dto.response.QuestionCreateResDto;

public interface QuestionService {
    QuestionCategoryListResDto getQuestionCategories();
    QuestionCreateResDto createQuestion(QuestionCreateReqDto req);
    void deleteQuestion(Long questionId);
}