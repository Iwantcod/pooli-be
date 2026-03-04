package com.pooli.question.service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.question.domain.dto.request.AttachmentReqDto;
import com.pooli.question.domain.dto.request.QuestionCreateReqDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class QuestionValidationServiceTest {

    @InjectMocks
    private QuestionValidationServiceImpl questionValidationService;

    @Nested
    @DisplayName("validateQuestionCreate 테스트")
    class ValidateQuestionCreate {

        @Test
        @DisplayName("첨부파일이 null이면 0 반환")
        void attachmentsNull() {
            // given
            QuestionCreateReqDto req = new QuestionCreateReqDto();
            req.setAttachments(null);

            // when
            int result = questionValidationService.validateQuestionCreate(req);

            // then
            assertEquals(0, result);
        }

        @Test
        @DisplayName("첨부파일이 2개면 2 반환")
        void attachmentsTwo() {
            // given
            QuestionCreateReqDto req = new QuestionCreateReqDto();
            req.setAttachments(List.of(
                    new AttachmentReqDto(),
                    new AttachmentReqDto()
            ));

            // when
            int result = questionValidationService.validateQuestionCreate(req);

            // then
            assertEquals(2, result);
        }

        @Test
        @DisplayName("첨부파일이 3개면 3 반환")
        void attachmentsThree() {
            // given
            QuestionCreateReqDto req = new QuestionCreateReqDto();
            req.setAttachments(List.of(
                    new AttachmentReqDto(),
                    new AttachmentReqDto(),
                    new AttachmentReqDto()
            ));

            // when
            int result = questionValidationService.validateQuestionCreate(req);

            // then
            assertEquals(3, result);
        }

        @Test
        @DisplayName("첨부파일이 4개 이상이면 예외 발생")
        void fileCountExceeded() {
            // given
            QuestionCreateReqDto req = new QuestionCreateReqDto();
            req.setAttachments(List.of(
                    new AttachmentReqDto(),
                    new AttachmentReqDto(),
                    new AttachmentReqDto(),
                    new AttachmentReqDto()
            ));

            // when & then
            assertThrows(ApplicationException.class,
                    () -> questionValidationService.validateQuestionCreate(req));
        }
    }
}