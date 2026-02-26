package com.pooli.question.service;

import com.pooli.common.dto.PagingResDto;
import com.pooli.question.domain.dto.request.QuestionCreateReqDto;
import com.pooli.question.domain.dto.response.QuestionCategoryListResDto;
import com.pooli.question.domain.dto.response.QuestionCreateResDto;
import com.pooli.question.domain.dto.response.QuestionListResDto;

public interface QuestionService {
    QuestionCategoryListResDto getQuestionCategories();
    QuestionCreateResDto createQuestion(QuestionCreateReqDto req);
    void deleteQuestion(Long questionId);

    PagingResDto<QuestionListResDto> selectQuestion(
            String categories,
            Boolean isAnswered,
            Integer page,
            Integer size
    );
}