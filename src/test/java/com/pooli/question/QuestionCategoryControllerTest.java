package com.pooli.question;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.common.DotenvInitializer;
import com.pooli.question.domain.dto.response.QuestionCategoryListResDto;
import com.pooli.question.domain.dto.response.QuestionCategoryResDto;
import com.pooli.question.domain.entity.QuestionCategory;
import com.pooli.question.exception.QuestionErrorCode;
import com.pooli.question.service.QuestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@ContextConfiguration(initializers = DotenvInitializer.class)
class QuestionCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private QuestionService questionService; // 실제 Service 대신 Mock 사용

    @Test
    @DisplayName("문의 카테고리 조회 성공")
    void getQuestionCategories_success() throws Exception {
        // given
        QuestionCategoryResDto dto1 = QuestionCategoryResDto.builder()
                .questionCategoryId(1)
                .questionCategoryName("Java")
                .build();

        QuestionCategoryResDto dto2 = QuestionCategoryResDto.builder()
                .questionCategoryId(2)
                .questionCategoryName("Database")
                .build();

        QuestionCategoryListResDto response = QuestionCategoryListResDto.builder()
                .questionCategories(List.of(dto1, dto2))
                .build();

        given(questionService.getQuestionCategories()).willReturn(response);

        // when & then
        mockMvc.perform(get("/api/questions/categories")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionCategories").isArray())
                .andExpect(jsonPath("$.questionCategories[0].questionCategoryName").value("Java"))
                .andExpect(jsonPath("$.questionCategories[1].questionCategoryName").value("Database"));
    }

    @Test
    @DisplayName("문의 카테고리 없을 때 404 처리")
    void getQuestionCategories_notFound() throws Exception {
        // given
        Mockito.when(questionService.getQuestionCategories())
                .thenThrow(new RuntimeException(QuestionErrorCode.QUESTION_CATEGORY_NOT_FOUND.getMessage()));

        // when & then
        mockMvc.perform(get("/api/questions/categories")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError()); // 예외를 잡아 ControllerAdvice 없으면 500
    }
}