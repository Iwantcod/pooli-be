package com.pooli.traffic.service;

/**
 * 10-tick 루프를 "tick당 1초 슬롯" 기준으로 맞춰 주는 페이서 인터페이스입니다.
 * 구현체는 현재 tick이 시작돼야 할 시각까지 대기한 뒤, 지연(lag) 시간을 반환합니다.
 */
public interface TrafficTickPacer {

    /**
     * @param orchestrationStartNano 오케스트레이션 시작 시점(System.nanoTime 기준)
     * @param tickNumber             1부터 시작하는 tick 번호
     * @return                       스케줄된 tick 시작 시각 대비 지연 시간(ms, 음수 없음)
     */
    long awaitTickStart(long orchestrationStartNano, int tickNumber);
}
