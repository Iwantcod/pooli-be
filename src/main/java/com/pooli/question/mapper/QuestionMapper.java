package com.pooli.question.mapper;

import com.pooli.question.domain.dto.QuestionAttachmentDto;
import com.pooli.question.domain.dto.response.QuestionListResDto;
import com.pooli.question.domain.entity.Question;
import com.pooli.question.domain.entity.QuestionCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface QuestionMapper {

    List<QuestionCategory> findAllActiveCategories();

    int insertQuestion(Question question); // questionId auto increment

    int insertQuestionAttachments(@Param("questionId") Long questionId,
                          @Param("attachments") List<QuestionAttachmentDto> attachments);

    int softDeleteQuestion(Long questionId);

    int softDeleteQuestionAttachments(Long questionId);

    List<QuestionListResDto> selectQuestionList(
            @Param("categories") List<Long> categories,
            @Param("isAnswered") Boolean isAnswered,
            @Param("offset") int offset,
            @Param("size") int size
    );

    Long countQuestionList(
            @Param("categories") List<Long> categories,
            @Param("isAnswered") Boolean isAnswered
    );
}
