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
public class QuestionAttachment {

    private Long questionAttachmentId;

    private Long questionId;

    private String s3Key;

    private Integer fileSize;

    private LocalDateTime createdAt;

    private LocalDateTime deletedAt;
}
