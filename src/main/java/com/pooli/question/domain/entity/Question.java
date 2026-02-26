package com.pooli.question.domain.entity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Question {

    private Long questionId;

    private Integer questionCategoryId;

    private Long lineId;

    private String title;

    private String content;

    private Boolean isAnswer;

    private LocalDateTime createdAt;

    private LocalDateTime deletedAt;
}
