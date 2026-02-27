package com.pooli.question.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AnswerMapper {

    // 해당 질문의 모든 답변 첨부파일 soft delete
    void softDeleteAnswerAttachments(@Param("questionId") Long questionId);

    // 해당 질문의 모든 답변 soft delete
    void softDeleteAnswers(@Param("questionId") Long questionId);
}