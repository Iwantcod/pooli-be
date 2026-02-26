package com.pooli.question.service;

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

    @Override
    @Transactional
    public QuestionCreateResDto createQuestion(QuestionCreateReqDto req) {
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
        List<String> s3Keys = null;
        if (req.getAttachments() != null && !req.getAttachments().isEmpty()) {
            questionMapper.insertQuestionAttachments(question.getQuestionId(), req.getAttachments());
            // Response용 s3Key 리스트 추출
            s3Keys = req.getAttachments().stream()
                    .map(QuestionAttachmentDto::getS3Key)
                    .toList();
        }

        // 3. Response 생성
        return QuestionCreateResDto.builder()
                .questionId(question.getQuestionId())
                .title(question.getTitle())
                .s3Keys(s3Keys)
                .build();
    }

}
