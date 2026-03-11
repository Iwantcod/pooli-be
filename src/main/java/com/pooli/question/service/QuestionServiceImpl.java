package com.pooli.question.service;

import com.pooli.auth.service.AuthUserDetails;
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
import com.pooli.question.mapper.AnswerMapper;
import com.pooli.question.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Profile("!traffic")
@RequiredArgsConstructor
public class QuestionServiceImpl implements QuestionService {

    private final QuestionMapper questionMapper;
    private final UploadService uploadService;
    private final QuestionValidationService questionValidationService;
    private final AnswerMapper answerMapper;

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
    public QuestionCreateResDto createQuestion(QuestionCreateReqDto req, Long sessionLineId) {


       int attachmentCount = questionValidationService.validateQuestionCreate(req);

        // 1. Question insert
        Question question = Question.builder()
                .questionCategoryId(req.getQuestionCategoryId())
                .lineId(sessionLineId)
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
    public void deleteQuestion(Long questionId, AuthUserDetails userDetails) {
        Long sessionLineId = userDetails.getLineId();
        boolean isAdmin = userDetails.getRoleNames().contains("ROLE_ADMIN");

        // 기본 검증
        questionValidationService.validateQuestionId(questionId);

        // 조회
        Question question = questionMapper.findQuestionById(questionId);

        // 존재 검증
        questionValidationService.validateQuestionExists(question);

        // 권한 검증
        questionValidationService.validateOwnerOrAdmin(
                question.getLineId(),
                sessionLineId,
                isAdmin
        );

        // 1. 질문 첨부파일 삭제
        questionMapper.softDeleteQuestionAttachments(questionId);

        // 2. 답변 첨부파일 삭제
        answerMapper.softDeleteAnswerAttachments(questionId);

        // 3. 답변 삭제
        answerMapper.softDeleteAnswers(questionId);

        // 4. 질문 삭제
        questionMapper.softDeleteQuestion(questionId);
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
    public QuestionResDto selectDetailQuestion(Long questionId, AuthUserDetails userDetails) {
        Long sessionLineId = userDetails.getLineId();
        boolean isAdmin = userDetails.getRoleNames().contains("ROLE_ADMIN");

        questionValidationService.validateQuestionId(questionId);

        Question question = questionMapper.findQuestionById(questionId);

        questionValidationService.validateQuestionExists(question);

        questionValidationService.validateOwnerOrAdmin(
                question.getLineId(),
                sessionLineId,
                isAdmin
        );

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
        Answer answer = answerMapper.findAnswerByQuestionId(questionId);
        if (answer != null) {
            List<AttachmentResDto> answerAttachments = answerMapper.findAnswerAttachments(answer.getAnswerId())
                    .stream()
                    .map(att -> AttachmentResDto.builder()
                            .url(att.getS3Key() != null ? uploadService.generateGetPresignedUrl(att.getS3Key()) : null)
                            .fileSize(att.getFileSize())
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
                .createdAt(question.getCreatedAt())
                .attachments(questionAttachments)
                .answer(answerDto)
                .build();
    }
}
