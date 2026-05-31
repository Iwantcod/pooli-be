package com.pooli.traffic.domain.restore;

/**
 * Redis replay Lua 실행 결과이다.
 *
 * @param status Lua가 반환한 처리 상태
 * @param message 실패 또는 skip 이유를 설명하는 메시지
 */
public record TrafficRestoreReplayResult(
        String status,
        String message
) {
}
