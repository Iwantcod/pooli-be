package com.pooli.permission.domain.entity;

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
public class PermissionLine {

    private Long lineId;

    private Long permissionId;

    private Boolean isEnable;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
