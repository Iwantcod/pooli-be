package com.pooli.traffic.mapper;

import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;

/**
 * done log INSERT 실행 전략을 표현하는 함수형 인터페이스입니다.
 *
 * <p>역할:
 * - `TrafficDeductDoneLogService.saveWithRetry()`는 재시도/중복 처리/예외 전파 같은
 *   공통 제어 흐름만 담당합니다.
 * - 실제 INSERT SQL 선택(일반 done log / non-retryable 종결 done log)은
 *   이 인터페이스 구현(메서드 레퍼런스)으로 주입합니다.
 *
 * <p>반환값 규칙:
 * - MyBatis INSERT 영향 행수(`int`)를 반환합니다.
 * - 서비스 레이어는 영향 행수가 정확히 1인지 검증해 비정상 결과(0, 2 이상)를
 *   예외 상황으로 처리합니다.
 */
@FunctionalInterface
public interface DoneLogInsertOperation {

    /**
     * done log INSERT를 수행하고 영향 행수를 반환합니다.
     *
     * @param doneLog 저장할 done log 엔티티
     * @return 영향 행수(정상 기대값: 1)
     */
    int insert(TrafficDeductDoneLog doneLog);
}

