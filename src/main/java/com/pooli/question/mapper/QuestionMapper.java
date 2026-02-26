package com.pooli.question.mapper;

import com.pooli.question.domain.dto.QuestionAttachmentDto;
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
}
