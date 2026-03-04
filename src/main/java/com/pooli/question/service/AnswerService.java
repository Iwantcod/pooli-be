package com.pooli.question.service;

import com.pooli.question.domain.dto.request.AnswerCreateReqDto;
import com.pooli.question.domain.dto.response.AnswerCreateResDto;

public interface AnswerService {
    AnswerCreateResDto createAnswer(AnswerCreateReqDto request, Long userId);
    void deleteAnswer(Long answerId);
}
