package com.pooli.family.domain.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "가족 역할")
public enum FamilyRole {

    OWNER,   // 가족 대표
    MEMBER   // 일반 구성원
}