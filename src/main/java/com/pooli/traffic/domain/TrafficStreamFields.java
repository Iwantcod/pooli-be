package com.pooli.traffic.domain;

/**
 * Redis Streams 레코드에서 사용하는 필드명을 모아둔 상수 클래스입니다.
 * 하드코딩 문자열 오타를 방지하고, producer/consumer가 동일 키를 사용하도록 강제합니다.
 */
public final class TrafficStreamFields {

    public static final String PAYLOAD = "payload";

    private TrafficStreamFields() {
    }
}
