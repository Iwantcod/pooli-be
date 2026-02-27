package com.pooli.question.service;

import com.pooli.common.dto.PagingResDto;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.common.service.UploadService;
import com.pooli.question.domain.dto.request.*;
import com.pooli.question.domain.dto.response.*;
import com.pooli.question.domain.entity.Answer;
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
    private final UploadService uploadService;
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

       int attachmentCount = questionValidationService.validateQuestionCreate(req);

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
            List<Long> categoryIds,
            Long lineId,
            Boolean isAnswered,
            Integer page,
            Integer size
    ) {

        questionValidationService.validatePagingParams(page, size);

        int offset = page * size;

        // 2️⃣ 조회
        List<QuestionListResDto> content =
                questionMapper.selectQuestionList(categoryIds, lineId, isAnswered, offset, size);

        Long totalElements =
                questionMapper.countQuestionList(categoryIds, lineId, isAnswered);

        int totalPages = (int) Math.ceil((double) totalElements / size);

        return PagingResDto.<QuestionListResDto>builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PagingResDto<QuestionListResDto> selectQuestionAdmin(
            List<Long> categoryIds,
            Boolean isAnswered,
            Long lineId,
            Integer page,
            Integer size
    ) {
        questionValidationService.validatePagingParams(page, size);

        int offset = page * size;

        // 조회
        List<QuestionListResDto> content =
                questionMapper.selectQuestionListAdmin(categoryIds, isAnswered, lineId, offset, size);

        Long totalElements =
                questionMapper.countQuestionListAdmin(categoryIds, isAnswered, lineId);

        int totalPages = (int) Math.ceil((double) totalElements / size);

        return PagingResDto.<QuestionListResDto>builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public QuestionResDto selectDetailQuestion(Long questionId) {

        questionValidationService.validateQuestionId(questionId);

        Question question = questionMapper.findQuestionById(questionId);

        questionValidationService.validateQuestionExists(question);

        // 2️. 질문 첨부파일 조회
        List<AttachmentResDto> questionAttachments = questionMapper.findQuestionAttachments(questionId)
                .stream()
                .map(att -> AttachmentResDto.builder()
                        .url(uploadService.generateGetPresignedUrl(att.getS3Key()))
                        .fileSize(att.getFileSize())
                        .build())
                .toList();

        // 3️. 답변 조회
        AnswerResDto answerDto = null;
        Answer answer = questionMapper.findAnswerByQuestionId(questionId);
        if (answer != null) {
            List<AttachmentResDto> answerAttachments = questionMapper.findAnswerAttachments(answer.getAnswerId())
                    .stream()
                    .map(att -> AttachmentResDto.builder()
                            .url(
                                    att.getS3Key() != null
                                            ? uploadService.generateGetPresignedUrl(att.getS3Key())
                                            : null
                            )
                            .fileSize(
                                    att.getFileSize() != null
                                            ? att.getFileSize()
                                            : null
                            )
                            .build())
                    .toList();

            answerDto = AnswerResDto.builder()
                    .answerId(answer.getAnswerId())
                    .userId(answer.getUserId())
                    .content(answer.getContent())
                    .createdAt(answer.getCreatedAt())
                    .attachments(answerAttachments)
                    .build();
        }

        // 4️. DTO 변환 후 반환
        return QuestionResDto.builder()
                .questionId(question.getQuestionId())
                .questionCategoryId(question.getQuestionCategoryId())
                .lineId(question.getLineId())
                .title(question.getTitle())
                .content(question.getContent())
                .isAnswer(question.getIsAnswer())
                .createdAt(
                        question.getCreatedAt() != null
                                ? question.getCreatedAt().toLocalDate()
                                : null
                )
                .attachments(questionAttachments)
                .answer(answerDto)
                .build();
    }
}
