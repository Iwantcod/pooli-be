package com.pooli.question.service;

import com.pooli.question.domain.dto.response.QuestionCategoryListResDto;

public interface QuestionService {
    QuestionCategoryListResDto getQuestionCategories();
}