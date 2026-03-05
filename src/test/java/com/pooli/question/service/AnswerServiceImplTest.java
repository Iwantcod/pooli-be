package com.pooli.question.service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.service.AlarmHistoryService;
import com.pooli.question.domain.dto.request.AnswerCreateReqDto;
import com.pooli.question.domain.dto.request.AttachmentReqDto;
import com.pooli.question.domain.dto.response.AnswerCreateResDto;
import com.pooli.question.domain.entity.Answer;
import com.pooli.question.domain.entity.Question;
import com.pooli.question.exception.AnswerErrorCode;
import com.pooli.question.mapper.AnswerMapper;
import com.pooli.question.mapper.QuestionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AnswerServiceImplTest {
    @Mock
    private AnswerMapper answerMapper;

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private QuestionValidationService questionValidationService;

    @Mock
    private AlarmHistoryService alarmHistoryService;

    @InjectMocks
    private AnswerServiceImpl answerService;

    @Captor
    ArgumentCaptor<Answer> answerCaptor;

    @Nested
    @DisplayName("createAnswer()")
    class CreateAnswer {

        @Test
        @DisplayName("정상적으로 답변 생성 성공")
        void createAnswer_success() {
            // given
            Long userId = 1L;
            Long questionId = 10L;

            Question question = Question.builder()
                    .questionId(questionId)
                    .lineId(100L)
                    .isAnswer(false)
                    .build();

            AnswerCreateReqDto req = new AnswerCreateReqDto();
            req.setQuestionId(questionId);
            req.setContent("답변 내용");
            AttachmentReqDto attachment = new AttachmentReqDto();
            attachment.setS3Key("s3-key");
            attachment.setFileSize(100);

            req.setAttachments(List.of(attachment));

            when(questionMapper.findQuestionById(questionId)).thenReturn(question);

            // when
            AnswerCreateResDto result = answerService.createAnswer(req, userId);

            // then
            assertThat(result).isNotNull();

            verify(answerMapper).insertAnswer(answerCaptor.capture());
            assertThat(answerCaptor.getValue().getUserId()).isEqualTo(userId);

            verify(answerMapper).insertAnswerAttachment(any());
            verify(questionMapper).updateQuestionIsAnswer(questionId, true);
            verify(alarmHistoryService)
                    .createAlarm(question.getLineId(), AlarmCode.QUESTION, AlarmType.ANSWER);
        }

        @Test
        @DisplayName("이미 답변이 존재하면 예외 발생")
        void createAnswer_alreadyExists() {
            // given
            Long questionId = 10L;

            Question question = Question.builder()
                    .questionId(questionId)
                    .isAnswer(true)
                    .build();

            AnswerCreateReqDto req = new AnswerCreateReqDto();
            req.setQuestionId(questionId);
            req.setContent("답변");

            when(questionMapper.findQuestionById(questionId)).thenReturn(question);

            // when & then
            assertThatThrownBy(() -> answerService.createAnswer(req, 1L))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining(AnswerErrorCode.ANSWER_ALREADY_EXISTS.getMessage());

            verify(answerMapper, never()).insertAnswer(any());
        }

        @Test
        @DisplayName("첨부파일이 없으면 insertAttachment 호출하지 않는다")
        void createAnswer_withoutAttachments() {
            Long questionId = 10L;
            Long userId = 1L;

            Question question = Question.builder()
                    .questionId(questionId)
                    .lineId(100L)
                    .isAnswer(false)
                    .build();

            AnswerCreateReqDto req = new AnswerCreateReqDto();
            req.setQuestionId(questionId);
            req.setContent("답변");

            when(questionMapper.findQuestionById(questionId)).thenReturn(question);

            answerService.createAnswer(req, userId);

            verify(answerMapper, never()).insertAnswerAttachment(any());
        }
    }

    @Nested
    @DisplayName("deleteAnswer()")
    class DeleteAnswer {

        @Test
        @DisplayName("정상적으로 삭제 성공")
        void deleteAnswer_success() {
            // given
            Long answerId = 1L;
            Long questionId = 10L;

            Answer answer = Answer.builder()
                    .answerId(answerId)
                    .questionId(questionId)
                    .build();

            when(answerMapper.findAnswerById(answerId)).thenReturn(answer);

            // when
            answerService.deleteAnswer(answerId);

            // then
            verify(answerMapper).softDeleteAnswerAttachmentsByAnswerId(answerId);
            verify(answerMapper).softDeleteAnswerById(answerId);
            verify(questionMapper).updateQuestionIsAnswer(questionId, false);
        }

        @Test
        @DisplayName("답변이 존재하지 않으면 예외 발생")
        void deleteAnswer_notFound() {
            // given
            Long answerId = 1L;
            when(answerMapper.findAnswerById(answerId)).thenReturn(null);

            // when & then
            assertThatThrownBy(() -> answerService.deleteAnswer(answerId))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining(AnswerErrorCode.ANSWER_NOT_FOUND.getMessage());

            verify(answerMapper, never()).softDeleteAnswerById(any());
        }
    }
}
