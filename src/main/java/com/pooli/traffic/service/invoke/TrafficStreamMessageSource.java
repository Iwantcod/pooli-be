package com.pooli.traffic.service.invoke;

/**
 * 스트림 메시지 유입 출처를 구분합니다.
 * NEW는 poller에서 읽은 신규 메시지, RECLAIM은 pending reclaim으로 다시 가져온 메시지입니다.
 */
public enum TrafficStreamMessageSource {
    NEW,
    RECLAIM
}
