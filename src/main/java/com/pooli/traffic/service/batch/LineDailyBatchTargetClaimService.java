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

    static final int PROCESSING_LEASE_TIMEOUT_SECONDS = 60;

    private final LineDailyBatchTargetMapper lineDailyBatchTargetMapper;

    /**
     * worker가 처리할 target row를 잠금 조회한 뒤 PROCESSING으로 전환한다.
     */
    @Transactional
    public List<LineDailyBatchTarget> claim(LocalDate usageDate, String workerId, int limit) {
        List<LineDailyBatchTarget> targets = lineDailyBatchTargetMapper.selectClaimableTargetsForUpdate(
                usageDate,
                PROCESSING_LEASE_TIMEOUT_SECONDS,
                limit
        );
        if (targets.isEmpty()) {
            return targets;
        }

        List<Long> ids = targets.stream()
                .map(LineDailyBatchTarget::getId)
                .toList();
        lineDailyBatchTargetMapper.markTargetsProcessing(ids, workerId);

        return targets;
    }
}
