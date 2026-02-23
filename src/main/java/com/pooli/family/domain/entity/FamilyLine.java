package com.pooli.family.domain.entity;

import com.pooli.family.domain.enums.FamilyRole;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FamilyLine {

    private Long familyId;
    private Long lineId;

    private FamilyRole role;

    private Boolean isPublic;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}