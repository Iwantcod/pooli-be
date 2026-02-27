package com.pooli.question.mapper;

import com.pooli.question.domain.dto.request.AttachmentReqDto;
import com.pooli.question.domain.dto.response.QuestionListResDto;
import com.pooli.question.domain.entity.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface QuestionMapper {

    List<QuestionCategory> findAllActiveCategories();

    int insertQuestion(Question question); // questionId auto increment

    int insertQuestionAttachments(@Param("questionId") Long questionId,
                          @Param("attachments") List<AttachmentReqDto> attachments);

    int softDeleteQuestion(Long questionId);

    int softDeleteQuestionAttachments(Long questionId);

    List<QuestionListResDto> selectQuestionList(
            @Param("categoryIds") List<Long> categoryIds,
            @Param("lineId") Long lineId,
            @Param("isAnswered") Boolean isAnswered,
            @Param("offset") int offset,
            @Param("size") int size
    );

    Question findQuestionById(@Param("questionId") Long questionId);

    List<QuestionAttachment> findQuestionAttachments(@Param("questionId") Long questionId);


    Long countQuestionList(
            @Param("categoryIds") List<Long> categoryIds,
            @Param("lineId") Long lineId,
            @Param("isAnswered") Boolean isAnswered
    );

    List<QuestionListResDto> selectQuestionListAdmin(
            @Param("categoryIds") List<Long> categoryIds,
            @Param("isAnswered") Boolean isAnswered,
            @Param("lineId") Long lineId,
            @Param("offset") int offset,
            @Param("size") int size
    );

    Long countQuestionListAdmin(
            @Param("categoryIds") List<Long> categoryIds,
            @Param("isAnswered") Boolean isAnswered,
            @Param("lineId") Long lineId
    );

    int updateQuestionIsAnswer(@Param("questionId") Long questionId,
                               @Param("isAnswer") Boolean isAnswer);

}
