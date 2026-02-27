package com.pooli.question.service;

import com.pooli.common.dto.PagingResDto;
import com.pooli.question.domain.dto.request.QuestionCreateReqDto;
import com.pooli.question.domain.dto.response.QuestionCategoryListResDto;
import com.pooli.question.domain.dto.response.QuestionCreateResDto;
import com.pooli.question.domain.dto.response.QuestionListResDto;

import java.util.List;

public interface QuestionService {
    QuestionCategoryListResDto getQuestionCategories();
    QuestionCreateResDto createQuestion(QuestionCreateReqDto req);
    void deleteQuestion(Long questionId);

    PagingResDto<QuestionListResDto> selectQuestion(
            List<Long> categoryIds,
            Long lineId,
            Boolean isAnswered,
            Integer page,
            Integer size
    );

    PagingResDto<QuestionListResDto> selectQuestionAdmin(
            List<Long> categoryIds,
            Boolean isAnswered,
            Long lineId, // 선택사항
            Integer page,
            Integer size
    );
}