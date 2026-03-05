package com.pooli.question.mapper;

import com.pooli.question.domain.entity.Answer;
import com.pooli.question.domain.entity.AnswerAttachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AnswerMapper {

    // 해당 질문의 모든 답변 첨부파일 soft delete
    void softDeleteAnswerAttachments(@Param("questionId") Long questionId);
    void softDeleteAnswers(@Param("questionId") Long questionId);


    Answer findAnswerByQuestionId(@Param("questionId") Long questionId);
    List<AnswerAttachment> findAnswerAttachments(@Param("answerId") Long answerId);
    Answer findAnswerById(@Param("answerId") Long answerId);

    // answerId 기준 단건 삭제
    void softDeleteAnswerAttachmentsByAnswerId(@Param("answerId") Long answerId);
    void softDeleteAnswerById(@Param("answerId") Long answerId);

    //  답변 생성
    void insertAnswer(Answer answer);
    void insertAnswerAttachment(AnswerAttachment attachment);
}