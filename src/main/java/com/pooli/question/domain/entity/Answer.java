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
public class Answer {

    private Long answerId;

    private Long userId;

    private Long questionId;

    private String content;

    private LocalDateTime createdAt;

    private LocalDateTime deletedAt;
}
