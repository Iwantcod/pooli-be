package com.pooli.user.domain.entity;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UserRole {

    private Long userId;
    private Integer roleId;

    // 날짜 + 시간
    private LocalDateTime createdAt;
}
