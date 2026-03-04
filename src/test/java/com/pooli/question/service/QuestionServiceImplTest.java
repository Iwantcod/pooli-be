package com.pooli.question.service;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.dto.PagingResDto;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.UploadErrorCode;
import com.pooli.common.service.UploadService;
import com.pooli.question.domain.dto.request.QuestionCreateReqDto;
import com.pooli.question.domain.dto.response.*;
import com.pooli.question.domain.entity.Question;
import com.pooli.question.domain.entity.QuestionCategory;
import com.pooli.question.exception.QuestionErrorCode;
import com.pooli.question.mapper.AnswerMapper;
import com.pooli.question.mapper.QuestionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuestionServiceImplTest {

    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private UploadService uploadService;
    @Spy
    private QuestionValidationServiceImpl questionValidationService;
    @Mock
    private AnswerMapper answerMapper;

    @InjectMocks
    private QuestionServiceImpl questionService;

    @Captor
    ArgumentCaptor<Question> questionCaptor;

    // ==========================
    // getQuestionCategories()
    // ==========================
    @Nested
    class GetCategories {

        @Test
        @DisplayName("카테고리 조회 성공")
        void success() {
            QuestionCategory category = QuestionCategory.builder()
                    .questionCategoryId(1)
                    .questionCategoryName("요금제")
                    .build();

            when(questionMapper.findAllActiveCategories())
                    .thenReturn(List.of(category));

            QuestionCategoryListResDto result =
                    questionService.getQuestionCategories();

            assertThat(result.getQuestionCategories()).hasSize(1);
            assertThat(result.getQuestionCategories().get(0)
                    .getQuestionCategoryName()).isEqualTo("요금제");
        }

        @Test
        @DisplayName("카테고리 없으면 예외")
        void notFound() {
            when(questionMapper.findAllActiveCategories())
                    .thenReturn(List.of());

            assertThatThrownBy(() -> questionService.getQuestionCategories())
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining(
                            QuestionErrorCode.QUESTION_CATEGORY_NOT_FOUND.getMessage()
                    );
        }
    }

    // ==========================
    // createQuestion()
    // ==========================
    @Nested
    class CreateQuestion {

        @Test
        @DisplayName("정상 생성")
        void success() {
            Long lineId = 100L;

            QuestionCreateReqDto req = new QuestionCreateReqDto();
            req.setQuestionCategoryId(1);
            req.setTitle("제목");
            req.setContent("내용");

            when(questionValidationService.validateQuestionCreate(req))
                    .thenReturn(0);

            questionService.createQuestion(req, lineId);

            verify(questionMapper).insertQuestion(questionCaptor.capture());
            Question saved = questionCaptor.getValue();

            assertThat(saved.getLineId()).isEqualTo(lineId);
            assertThat(saved.getIsAnswer()).isFalse();
        }

        @Test
        @DisplayName("첨부파일 존재하면 insertAttachments 호출")
        void withAttachments() {
            Long lineId = 100L;

            QuestionCreateReqDto req = new QuestionCreateReqDto();
            req.setQuestionCategoryId(1);
            req.setTitle("제목");
            req.setContent("내용");

            when(questionValidationService.validateQuestionCreate(req))
                    .thenReturn(2);

            questionService.createQuestion(req, lineId);

            verify(questionMapper)
                    .insertQuestionAttachments(any(), any());
        }

        @Test
        @DisplayName("첨부파일 3개 초과 예외")
        void fileExceeded() {
            QuestionCreateReqDto req = new QuestionCreateReqDto();

            when(questionValidationService.validateQuestionCreate(req))
                    .thenThrow(new ApplicationException(UploadErrorCode.FILE_COUNT_EXCEEDED));

            assertThatThrownBy(() ->
                    questionService.createQuestion(req, 1L))
                    .isInstanceOf(ApplicationException.class);
        }
    }

    // ==========================
    // deleteQuestion()
    // ==========================
    @Nested
    class DeleteQuestion {

        @Test
        @DisplayName("정상 삭제")
        void success() {
            Long questionId = 1L;
            Long lineId = 100L;

            AuthUserDetails user = mock(AuthUserDetails.class);
            when(user.getLineId()).thenReturn(lineId);
            when(user.getRoleNames()).thenReturn(List.of("ROLE_USER"));

            Question question = Question.builder()
                    .questionId(questionId)
                    .lineId(lineId)
                    .build();

            when(questionMapper.findQuestionById(questionId))
                    .thenReturn(question);

            questionService.deleteQuestion(questionId, user);

            verify(questionMapper).softDeleteQuestion(questionId);
            verify(answerMapper).softDeleteAnswers(questionId);
        }
    }

    @Test
    @DisplayName("본인 질문 아니면 예외")
    void delete_fail_notOwner() {
        Long questionId = 1L;

        AuthUserDetails user = mock(AuthUserDetails.class);
        when(user.getLineId()).thenReturn(999L);
        when(user.getRoleNames()).thenReturn(List.of("ROLE_USER"));

        Question question = Question.builder()
                .questionId(questionId)
                .lineId(100L)
                .build();

        when(questionMapper.findQuestionById(questionId))
                .thenReturn(question);

        assertThrows(ApplicationException.class,
                () -> questionService.deleteQuestion(questionId, user));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN"})
    @DisplayName("관리자는 타인 질문 삭제 가능")
    void adminCanDelete(String role) {

        Long questionId = 1L;

        AuthUserDetails user = mock(AuthUserDetails.class);
        when(user.getLineId()).thenReturn(999L);
        when(user.getRoleNames()).thenReturn(List.of(role));

        Question question = Question.builder()
                .questionId(questionId)
                .lineId(100L)
                .build();

        when(questionMapper.findQuestionById(questionId))
                .thenReturn(question);

        assertDoesNotThrow(() ->
                questionService.deleteQuestion(questionId, user));
    }

    // ==========================
    // selectQuestion()
    // ==========================
    @Test
    @DisplayName("페이징 조회 성공")
    void selectQuestion_success() {
        when(questionMapper.selectQuestionList(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        when(questionMapper.countQuestionList(any(), any(), any()))
                .thenReturn(10L);

        PagingResDto<QuestionListResDto> result =
                questionService.selectQuestion(null, 1L, null, 0, 5);

        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getTotalElements()).isEqualTo(10L);
    }

    // ==========================
    // selectDetailQuestion()
    // ==========================
    @Test
    @DisplayName("상세 조회 성공")
    void selectDetail_success() {
        Long questionId = 1L;
        Long lineId = 100L;

        AuthUserDetails user = mock(AuthUserDetails.class);
        when(user.getLineId()).thenReturn(lineId);
        when(user.getRoleNames()).thenReturn(List.of("ROLE_USER"));

        Question question = Question.builder()
                .questionId(questionId)
                .lineId(lineId)
                .questionCategoryId(1)
                .title("제목")
                .content("내용")
                .isAnswer(false)
                .createdAt(LocalDateTime.now())
                .build();

        when(questionMapper.findQuestionById(questionId))
                .thenReturn(question);

        when(questionMapper.findQuestionAttachments(questionId))
                .thenReturn(List.of());

        when(answerMapper.findAnswerByQuestionId(questionId))
                .thenReturn(null);

        QuestionResDto result =
                questionService.selectDetailQuestion(questionId, user);

        assertThat(result.getQuestionId()).isEqualTo(questionId);
        assertThat(result.getAnswer()).isNull();
    }
}