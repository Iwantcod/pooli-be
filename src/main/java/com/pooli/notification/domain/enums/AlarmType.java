package com.pooli.notification.domain.enums;

public enum AlarmType {
    /*
    =============== 관리자 정책 활성화/비활성화 ================
     */
    ACTIVATE_POLICY, // 새로운 정책이 활성화되었습니다.
    DEACTIVATE_POLICY, // 새로운 정책이 비활성화되었습니다.

    /*
    =============== 차단 정책(반복적/즉시) ================
     */
    CREATE_REPEAT_BLOCK, // 반복적 차단 정책이 생성되었습니다.
    UPDATE_REPEAT_BLOCK, // 반복적 차단 정책이 수정되었습니다.
    DELETE_REPEAT_BLOCK, // 반복적 차단 정책이 삭제되었습니다.
    CREATE_IMMEDIATE_BLOCK, // 즉시 차단 정책이 생성되었습니다.
    UPDATE_IMMEDIATE_BLOCK, // 즉시 차단 정책이 수정되었습니다.
    DELETE_IMMEDIATE_BLOCK, // 즉시 차단 정책이 삭제되었습니다.

    /*
    =============== 회선 별 데이터 사용 제한 정책 ================
     */
    POLICY_CREATE_SHAREDATA_LIMIT, // 월 공유 데이터 사용량 제한이 생성되었습니다.
    POLICY_UPDATE_SHAREDATA_LIMIT, // 월 공유 데이터 사용량 제한이 수정되었습니다.
    POLICY_DELETE_SHAREDATA_LIMIT, // 월 공유 데이터 사용량 제한이 삭제되었습니다.
    POLICY_CREATE_DAYDATA_LIMIT, // 일 개인 데이터 사용량 제한이 생성되었습니다.
    POLICY_UPDATE_DAYDATA_LIMIT, // 일 개인 데이터 사용량 제한이 수정되었습니다.
    POLICY_DELETE_DAYDATA_LIMIT, // 일 개인 데이터 사용량 제한이 삭제되었습니다.
    /*
    =============== 앱별 데이터 제한 정책 ================
     */
    POLICY_CREATE_APP_USAGE_LIMIT, // 앱 데이터의 사용량 제한이 생성되었습니다.
    POLICY_UPDATE_APP_USAGE_LIMIT, // 앱 데이터의 사용량 제한이 수정되었습니다.
    POLICY_DELETE_APP_USAGE_LIMIT, // 앱 데이터의 사용량 제한이 삭제되었습니다.
    POLICY_CREATE_DATA_SPEED_LIMIT, // 앱 데이터의 속도 제한이 생성되었습니다.
    POLICY_UPDATE_DATA_SPEED_LIMIT, // 앱 데이터의 속도 제한이 수정되었습니다.
    POLICY_DELETE_DATA_SPEED_LIMIT, // 앱 데이터의 속도 제한이 삭제되었습니다.
    POLICY_ADD_WHITELIST,  // 앱 정책 화이트리스트가 추가되었습니다.
    POLICY_DELETE_WHITELIST, // 앱 정책 화이트리스트가 삭제되었습니다.



    // QUESTION
    ANSWER,

    // OTHERS
    NOTIFICATION,

    // FAMILY (공유풀)
    SHARED_POOL_CONTRIBUTION,
    SHARED_POOL_THRESHOLD_CHANGE,
}
