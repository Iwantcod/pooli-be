package com.pooli.notification.domain.enums;

public enum NotificationTargetType {
    DIRECT,     // 특정 lineId 직접 지정
    ALL,        // 전체 회선
    OWNER,      // OWNER 역할만
    MEMBER      // MEMBER 역할만
}