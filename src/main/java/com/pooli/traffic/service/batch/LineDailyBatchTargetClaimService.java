package com.pooli.traffic.service.batch;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.traffic.domain.batch.LineDailyBatchTarget;
import com.pooli.traffic.mapper.LineDailyBatchTargetMapper;

import lombok.RequiredArgsConstructor;

/**
 * LINE_DAILY_BATCH_TARGET row 선점만 담당한다.
 * Redis 조회와 DB 반영은 이 서비스의 짧은 트랜잭션이 끝난 뒤 worker가 수행한다.
 */
@Service
@RequiredArgsConstructor
public class LineDailyBatchTargetClaimService {

    static final int PROCESSING_LEASE_TIMEOUT_SECONDS = 300; // 5분

    private final LineDailyBatchTargetMapper lineDailyBatchTargetMapper;

    /**
     * worker가 처리할 target row를 잠금 조회한 뒤 PROCESSING으로 전환한다.
     *
     * @throws IllegalArgumentException workerId가 비어 있거나 limit이 양수가 아닐 때
     */
    @Transactional
    public List<LineDailyBatchTarget> claim(LocalDate usageDate, String workerId, int limit) {
        // 1. 잘못된 worker/limit 입력은 DB claim query와 PROCESSING 전환에 전달하지 않는다.
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be null or blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }

        // 2. claim 가능한 target row를 짧은 트랜잭션 안에서 잠금 조회한다.
        List<LineDailyBatchTarget> targets = lineDailyBatchTargetMapper.selectClaimableTargetsForUpdate(
                usageDate,
                PROCESSING_LEASE_TIMEOUT_SECONDS,
                limit
        );
        if (targets.isEmpty()) {
            return targets;
        }

        // 3. 조회한 row만 현재 worker 소유 PROCESSING 상태로 전환한다.
        List<Long> ids = targets.stream()
                .map(LineDailyBatchTarget::getId)
                .toList();
        lineDailyBatchTargetMapper.markTargetsProcessing(ids, workerId);

        return targets;
    }
}
