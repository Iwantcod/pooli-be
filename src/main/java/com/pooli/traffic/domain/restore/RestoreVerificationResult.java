package com.pooli.traffic.domain.restore;

/**
 * Redis 복구 전체 검증과 자동 보정 결과이다.
 *
 * @param matchedCount Redis 값이 기준값과 이미 일치한 field 수
 * @param correctedCount 기준값으로 자동 보정한 field 수
 * @param failedCorrectionCount 자동 보정에 실패한 field 수
 * @param idempotencyCleanedCount 최종 성공 후 삭제한 restore idempotency key 수
 */
public record RestoreVerificationResult(
        long matchedCount,
        long correctedCount,
        long failedCorrectionCount,
        long idempotencyCleanedCount
) {
}
