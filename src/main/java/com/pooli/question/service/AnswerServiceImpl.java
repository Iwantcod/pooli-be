package com.pooli.question.service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.line.mapper.LineMapper;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.service.AlarmHistoryService;
import com.pooli.question.domain.dto.request.AnswerCreateReqDto;
import com.pooli.question.domain.dto.request.AttachmentReqDto;
import com.pooli.question.domain.dto.response.AnswerCreateResDto;
import com.pooli.question.domain.entity.Answer;
import com.pooli.question.domain.entity.AnswerAttachment;
import com.pooli.question.domain.entity.Question;
import com.pooli.question.exception.AnswerErrorCode;
import com.pooli.question.mapper.AnswerMapper;
import com.pooli.question.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AnswerServiceImpl implements AnswerService {

    private final AnswerMapper answerMapper;
    private final QuestionMapper questionMapper;
    private final QuestionValidationService questionValidationService;
    private final AlarmHistoryService alarmHistoryService;
    private final LineMapper lineMapper;


    @Transactional
    @Override
    public AnswerCreateResDto createAnswer(AnswerCreateReqDto req, Long userId) {

        // 질문 존재 여부 체크
        questionValidationService.validateQuestionId(req.getQuestionId());
        Question question = questionMapper.findQuestionById(req.getQuestionId());
        questionValidationService.validateQuestionExists(question);

        // 이미 답변이 존재하면 예외
        boolean hasAnswer = question.getIsAnswer() != null && question.getIsAnswer();
        if (hasAnswer) {
            throw new ApplicationException(AnswerErrorCode.ANSWER_ALREADY_EXISTS);
        }

        // 1. Answer 엔티티 생성 및 DB insert
        Answer answer = Answer.builder()
                .userId(userId)
                .questionId(req.getQuestionId())
                .content(req.getContent())
                .build();
        answerMapper.insertAnswer(answer);

        // 2. Attachments insert
        List<AttachmentReqDto> attachments = req.getAttachments();
        if (attachments != null && !attachments.isEmpty()) {
            for (AttachmentReqDto a : attachments) {
                AnswerAttachment attachment = AnswerAttachment.builder()
                        .answerId(answer.getAnswerId())
                        .s3Key(a.getS3Key())
                        .fileSize(a.getFileSize())
                        .build();
                answerMapper.insertAnswerAttachment(attachment);
            }
        }

        // 3. 질문 isAnswer 업데이트
        questionMapper.updateQuestionIsAnswer(req.getQuestionId(), true);
        alarmHistoryService.createAlarm(question.getLineId(), AlarmCode.QUESTION, AlarmType.ANSWER, null);

        // 4. 응답 DTO 반환
        return AnswerCreateResDto.builder()
                .answerId(answer.getAnswerId())
                .build();
    }

    @Transactional
    @Override
    public void deleteAnswer(Long answerId) {

        // 1. 답변 조회
        Answer answer = answerMapper.findAnswerById(answerId);
        if (answer == null) {
            throw new ApplicationException(AnswerErrorCode.ANSWER_NOT_FOUND); // 예: 404
        }
        Long questionId = answer.getQuestionId();

        // 2. 첨부파일 soft delete
        answerMapper.softDeleteAnswerAttachmentsByAnswerId(answerId);

        // 3. 답변 soft delete
        answerMapper.softDeleteAnswerById(answerId);

        // 4. 질문 isAnswer 업데이트
        questionMapper.updateQuestionIsAnswer(questionId, false);

    }
}