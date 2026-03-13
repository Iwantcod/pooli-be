package com.pooli.policy.service;

public interface PolicyHistoryService {
    /**
     * 정책 변경 이력을 MongoDB에 저장합니다.
     * 
     * @param tableName 원본 RDB 테이블명
     * @param event 이벤트 유형 (CREATE, UPDATE, DELETE)
     * @param targetId 대상 레코드 PK
     * @param before 변경 전 객체 (null 허용)
     * @param after 변경 후 객체 (null 허용)
     */
    void log(String tableName, String event, Object targetId, Object before, Object after);
}
