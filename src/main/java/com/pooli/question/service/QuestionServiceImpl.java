package com.pooli.question.service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.question.domain.dto.response.QuestionCategoryListResDto;
import com.pooli.question.domain.dto.response.QuestionCategoryResDto;
import com.pooli.question.domain.entity.QuestionCategory;
import com.pooli.question.exception.QuestionErrorCode;
import com.pooli.question.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class QuestionServiceImpl implements QuestionService {

    private final QuestionMapper questionMapper;

    @Override
    public QuestionCategoryListResDto getQuestionCategories() {
        List<QuestionCategory> categories =
                questionMapper.findAllActiveCategories();

        if (categories.isEmpty()) {
            throw new ApplicationException(QuestionErrorCode.QUESTION_CATEGORY_NOT_FOUND);
        }

        List<QuestionCategoryResDto> dtoList =
                categories.stream()
                        .map(c -> QuestionCategoryResDto.builder()
                                .questionCategoryId(c.getQuestionCategoryId())
                                .questionCategoryName(c.getQuestionCategoryName())
                                .build())
                        .toList();

        return QuestionCategoryListResDto.builder()
                .questionCategories(dtoList)
                .build();
    }

}
