package com.pooli.question.service;

import com.pooli.common.dto.PagingResDto;
import com.pooli.common.exception.ApplicationException;
import com.pooli.question.domain.dto.QuestionAttachmentDto;
import com.pooli.question.domain.dto.request.*;
import com.pooli.question.domain.dto.response.*;
import com.pooli.question.domain.entity.Question;
import com.pooli.question.domain.entity.QuestionCategory;
import com.pooli.question.exception.QuestionErrorCode;
import com.pooli.question.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class QuestionServiceImpl implements QuestionService {

    private final QuestionMapper questionMapper;
    private final QuestionValidationService questionValidationService;

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

    @Override
    @Transactional
    public QuestionCreateResDto createQuestion(QuestionCreateReqDto req) {

        int attachmentCount =
                req.getAttachments() == null ? 0 : req.getAttachments().size();

        // 1. Question insert
        Question question = Question.builder()
                .questionCategoryId(req.getQuestionCategoryId())
                .lineId(req.getLineId())
                .title(req.getTitle())
                .content(req.getContent())
                .isAnswer(false)
                .createdAt(LocalDateTime.now())
                .build();

        questionMapper.insertQuestion(question); // insert 후 questionId 채워짐

        // 2. Attachments insert
        if (attachmentCount > 0) {
            questionMapper.insertQuestionAttachments(question.getQuestionId(), req.getAttachments());
        }

        // 3. Response 생성
        return QuestionCreateResDto.builder()
                .questionId(question.getQuestionId())
                .title(question.getTitle())
                .build();
    }

    @Override
    @Transactional
    public void deleteQuestion(Long questionId) {

        int affected = questionMapper.softDeleteQuestion(questionId);

        if (affected == 0) {
            throw new ApplicationException(
                    QuestionErrorCode.QUESTION_NOT_FOUND
            );
        }

        questionMapper.softDeleteQuestionAttachments(questionId);
    }

    @Override
    @Transactional(readOnly = true)
    public PagingResDto<QuestionListResDto> selectQuestion(
            String categories,
            Boolean isAnswered,
            Integer page,
            Integer size
    ) {

        List<Long> categoryList = Arrays.stream(categories.split(","))
                .map(String::trim)
                .map(Long::parseLong)
                .toList();

        int offset = page * size;

        List<QuestionListResDto> content =
                questionMapper.selectQuestionList(categoryList, isAnswered, offset, size);

        Long totalElements =
                questionMapper.countQuestionList(categoryList, isAnswered);

        int totalPages = (int) Math.ceil((double) totalElements / size);

        return PagingResDto.<QuestionListResDto>builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }

}
