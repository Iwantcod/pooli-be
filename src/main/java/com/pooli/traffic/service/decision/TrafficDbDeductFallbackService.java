package com.pooli.traffic.service.decision;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.policy.domain.dto.response.ImmediateBlockResDto;
import com.pooli.policy.domain.dto.response.PolicyActivationSnapshotResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockDayResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import com.pooli.policy.domain.entity.AppPolicy;
import com.pooli.policy.domain.entity.LineLimit;
import com.pooli.policy.domain.enums.DayOfWeek;
import com.pooli.policy.mapper.AppPolicyMapper;
import com.pooli.policy.mapper.ImmediateBlockMapper;
import com.pooli.policy.mapper.LineLimitMapper;
import com.pooli.policy.mapper.PolicyBackOfficeMapper;
import com.pooli.policy.mapper.RepeatBlockMapper;
import com.pooli.traffic.domain.TrafficDeductExecutionContext;
import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.mapper.TrafficDbUsageMapper;
import com.pooli.traffic.mapper.TrafficRefillSourceMapper;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

import lombok.RequiredArgsConstructor;

/**
 * Redis 차감 장애 시 DB에서 정책 판정을 수행해 차감을 이어가는 fallback 서비스입니다.
 * App Speed 정책은 Redis 정상 경로에서만 적용하며, DB fallback 경로에서는 의도적으로 제외합니다.
 */
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficDbDeductFallbackService {

    private static final int POLICY_REPEAT_BLOCK_ID = 1;
    private static final int POLICY_IMMEDIATE_BLOCK_ID = 2;
    private static final int POLICY_LINE_LIMIT_SHARED_ID = 3;
    private static final int POLICY_LINE_LIMIT_DAILY_ID = 4;
    private static final int POLICY_APP_DATA_ID = 5;
    private static final int POLICY_APP_WHITELIST_ID = 7;

    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final PolicyBackOfficeMapper policyBackOfficeMapper;
    private final LineLimitMapper lineLimitMapper;
    private final AppPolicyMapper appPolicyMapper;
    private final ImmediateBlockMapper immediateBlockMapper;
    private final RepeatBlockMapper repeatBlockMapper;
    private final TrafficRefillSourceMapper trafficRefillSourceMapper;
    private final TrafficDbUsageMapper trafficDbUsageMapper;

    /**
     * DB fallback 정책 판정 + 차감 + 집계 갱신을 단일 트랜잭션으로 수행합니다.
     */
    @Transactional
    public TrafficLuaExecutionResult deduct(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            long requestedBytes,
            TrafficDeductExecutionContext context
    ) {
        if (!isPayloadValid(poolType, payload)) {
            return buildResult(-1L, TrafficLuaStatus.ERROR);
        }

        LocalDateTime now = LocalDateTime.now(trafficRedisRuntimePolicy.zoneId());
        LocalDate usageDate = now.toLocalDate();
        YearMonth targetMonth = YearMonth.from(now);

        Map<Integer, Boolean> policyActivation = loadPolicyActivation();
        Optional<LineLimit> lineLimitOptional = lineLimitMapper.getExistLineLimitByLineId(payload.getLineId());
        LineLimit lineLimit = lineLimitOptional.orElse(null);
        AppPolicy appPolicy = appPolicyMapper.findEntityExistByLineIdAndAppId(payload.getLineId(), payload.getAppId())
                .orElse(null);

        boolean whitelistBypass = isWhitelistBypass(policyActivation, appPolicy);

        if (!whitelistBypass) {
            if (isPolicyEnabled(policyActivation, POLICY_IMMEDIATE_BLOCK_ID)
                    && isImmediateBlocked(payload.getLineId(), now)) {
                return buildResult(0L, TrafficLuaStatus.BLOCKED_IMMEDIATE);
            }

            if (isPolicyEnabled(policyActivation, POLICY_REPEAT_BLOCK_ID)
                    && isRepeatBlocked(payload.getLineId(), now.toLocalTime(), toLuaDayOfWeek(now))) {
                return buildResult(0L, TrafficLuaStatus.BLOCKED_REPEAT);
            }
        }

        long currentAmount = selectRemainingAmountForUpdate(poolType, payload);
        long normalizedRequestedBytes = Math.max(0L, requestedBytes);
        long policyCappedTarget = normalizedRequestedBytes;
        TrafficLuaStatus finalStatus = TrafficLuaStatus.OK;

        if (!whitelistBypass) {
            long beforeDailyCap = policyCappedTarget;
            policyCappedTarget = applyDailyLimitIfEnabled(
                    policyActivation,
                    lineLimit,
                    payload.getLineId(),
                    usageDate,
                    policyCappedTarget
            );
            if (policyCappedTarget <= 0) {
                return buildResult(0L, TrafficLuaStatus.HIT_DAILY_LIMIT);
            }
            if (policyCappedTarget < beforeDailyCap) {
                finalStatus = TrafficLuaStatus.HIT_DAILY_LIMIT;
            }

            if (poolType == TrafficPoolType.SHARED) {
                long beforeSharedCap = policyCappedTarget;
                policyCappedTarget = applySharedMonthlyLimitIfEnabled(
                        policyActivation,
                        lineLimit,
                        payload.getLineId(),
                        targetMonth,
                        policyCappedTarget
                );
                if (policyCappedTarget <= 0) {
                    return buildResult(0L, TrafficLuaStatus.HIT_MONTHLY_SHARED_LIMIT);
                }
                if (policyCappedTarget < beforeSharedCap) {
                    finalStatus = TrafficLuaStatus.HIT_MONTHLY_SHARED_LIMIT;
                }
            }

            long beforeAppDailyCap = policyCappedTarget;
            policyCappedTarget = applyAppDailyLimitIfEnabled(
                    policyActivation,
                    appPolicy,
                    payload.getLineId(),
                    payload.getAppId(),
                    usageDate,
                    policyCappedTarget
            );
            if (policyCappedTarget <= 0) {
                return buildResult(0L, TrafficLuaStatus.HIT_APP_DAILY_LIMIT);
            }
            if (policyCappedTarget < beforeAppDailyCap) {
                finalStatus = TrafficLuaStatus.HIT_APP_DAILY_LIMIT;
            }
        }

        long answer = Math.min(currentAmount, policyCappedTarget);
        boolean insufficientBalance = currentAmount < policyCappedTarget;
        if (insufficientBalance) {
            finalStatus = TrafficLuaStatus.NO_BALANCE;
        }
        if (answer <= 0) {
            // answer==0은 잔량 부족 성격이므로 NO_BALANCE로 리필 경로를 연다.
            return buildResult(0L, TrafficLuaStatus.NO_BALANCE);
        }

        int updatedRows = deductRemainingAmount(poolType, payload, answer);
        if (updatedRows <= 0) {
            long reloadedAmount = selectRemainingAmount(poolType, payload);
            long reloadedPolicyCappedTarget = normalizedRequestedBytes;
            TrafficLuaStatus reloadedStatus = TrafficLuaStatus.OK;

            // 동시성으로 잔량이 바뀐 재시도 경로에서도 최초 계산과 동일한 정책 제한을 다시 적용해
            // 제한 우회 없이 `deductRemainingAmount`가 항상 안전한 차감량만 사용하도록 보장합니다.
            if (!whitelistBypass) {
                long beforeDailyCap = reloadedPolicyCappedTarget;
                reloadedPolicyCappedTarget = applyDailyLimitIfEnabled(
                        policyActivation,
                        lineLimit,
                        payload.getLineId(),
                        usageDate,
                        reloadedPolicyCappedTarget
                );
                if (reloadedPolicyCappedTarget <= 0) {
                    return buildResult(0L, TrafficLuaStatus.HIT_DAILY_LIMIT);
                }
                if (reloadedPolicyCappedTarget < beforeDailyCap) {
                    reloadedStatus = TrafficLuaStatus.HIT_DAILY_LIMIT;
                }

                if (poolType == TrafficPoolType.SHARED) {
                    long beforeSharedCap = reloadedPolicyCappedTarget;
                    reloadedPolicyCappedTarget = applySharedMonthlyLimitIfEnabled(
                            policyActivation,
                            lineLimit,
                            payload.getLineId(),
                            targetMonth,
                            reloadedPolicyCappedTarget
                    );
                    if (reloadedPolicyCappedTarget <= 0) {
                        return buildResult(0L, TrafficLuaStatus.HIT_MONTHLY_SHARED_LIMIT);
                    }
                    if (reloadedPolicyCappedTarget < beforeSharedCap) {
                        reloadedStatus = TrafficLuaStatus.HIT_MONTHLY_SHARED_LIMIT;
                    }
                }

                long beforeAppDailyCap = reloadedPolicyCappedTarget;
                reloadedPolicyCappedTarget = applyAppDailyLimitIfEnabled(
                        policyActivation,
                        appPolicy,
                        payload.getLineId(),
                        payload.getAppId(),
                        usageDate,
                        reloadedPolicyCappedTarget
                );
                if (reloadedPolicyCappedTarget <= 0) {
                    return buildResult(0L, TrafficLuaStatus.HIT_APP_DAILY_LIMIT);
                }
                if (reloadedPolicyCappedTarget < beforeAppDailyCap) {
                    reloadedStatus = TrafficLuaStatus.HIT_APP_DAILY_LIMIT;
                }
            }

            long reloadedAnswer = Math.min(reloadedAmount, reloadedPolicyCappedTarget);
            boolean reloadedInsufficientBalance = reloadedAmount < reloadedPolicyCappedTarget;
            if (reloadedInsufficientBalance) {
                reloadedStatus = TrafficLuaStatus.NO_BALANCE;
            }
            if (reloadedAnswer <= 0) {
                return buildResult(0L, TrafficLuaStatus.NO_BALANCE);
            }

            updatedRows = deductRemainingAmount(poolType, payload, reloadedAnswer);
            if (updatedRows <= 0) {
                return buildResult(0L, reloadedStatus);
            }
            answer = reloadedAnswer;
            finalStatus = reloadedStatus;
        }

        trafficDbUsageMapper.upsertDailyTotalUsage(payload.getLineId(), usageDate, answer);
        trafficDbUsageMapper.upsertDailyAppUsage(payload.getLineId(), payload.getAppId(), usageDate, answer);
        if (poolType == TrafficPoolType.SHARED && payload.getFamilyId() != null && payload.getFamilyId() > 0) {
            trafficDbUsageMapper.upsertFamilySharedUsageDaily(payload.getFamilyId(), payload.getLineId(), usageDate, answer);
        }

        return buildResult(answer, finalStatus);
    }

    /**
     * Lua daily limit 분기와 동일하게 answer를 제한합니다.
     */
    private long applyDailyLimitIfEnabled(
            Map<Integer, Boolean> policyActivation,
            LineLimit lineLimit,
            Long lineId,
            LocalDate usageDate,
            long answer
    ) {
        if (!isPolicyEnabled(policyActivation, POLICY_LINE_LIMIT_DAILY_ID)) {
            return answer;
        }

        long dailyLimit = resolveDailyLimit(lineLimit);
        if (dailyLimit < 0) {
            return answer;
        }

        long dailyUsed = normalizeNonNegative(trafficDbUsageMapper.selectDailyTotalUsage(lineId, usageDate));
        long dailyRemaining = Math.max(0L, dailyLimit - dailyUsed);
        return Math.min(answer, dailyRemaining);
    }

    /**
     * Lua shared monthly limit 분기와 동일하게 answer를 제한합니다.
     */
    private long applySharedMonthlyLimitIfEnabled(
            Map<Integer, Boolean> policyActivation,
            LineLimit lineLimit,
            Long lineId,
            YearMonth targetMonth,
            long answer
    ) {
        if (!isPolicyEnabled(policyActivation, POLICY_LINE_LIMIT_SHARED_ID)) {
            return answer;
        }

        long sharedLimit = resolveSharedMonthlyLimit(lineLimit);
        if (sharedLimit < 0) {
            return answer;
        }

        LocalDate monthStart = targetMonth.atDay(1);
        LocalDate nextMonthStart = targetMonth.plusMonths(1).atDay(1);
        long sharedUsed = normalizeNonNegative(
                trafficDbUsageMapper.selectMonthlySharedUsageByLine(lineId, monthStart, nextMonthStart)
        );
        long monthlyRemaining = Math.max(0L, sharedLimit - sharedUsed);
        return Math.min(answer, monthlyRemaining);
    }

    /**
     * Lua app daily limit 분기와 동일하게 answer를 제한합니다.
     */
    private long applyAppDailyLimitIfEnabled(
            Map<Integer, Boolean> policyActivation,
            AppPolicy appPolicy,
            Long lineId,
            Integer appId,
            LocalDate usageDate,
            long answer
    ) {
        if (!isPolicyEnabled(policyActivation, POLICY_APP_DATA_ID)) {
            return answer;
        }

        long appDailyLimit = resolveAppDailyLimit(appPolicy);
        if (appDailyLimit < 0) {
            return answer;
        }

        long appDailyUsed = normalizeNonNegative(trafficDbUsageMapper.selectDailyAppUsage(lineId, appId, usageDate));
        long appDailyRemaining = Math.max(0L, appDailyLimit - appDailyUsed);
        return Math.min(answer, appDailyRemaining);
    }

    /**
     * line 기준 즉시 차단 활성 상태를 확인합니다.
     */
    private boolean isImmediateBlocked(Long lineId, LocalDateTime now) {
        ImmediateBlockResDto immediateBlockResDto = immediateBlockMapper.selectImmediateBlockPolicy(lineId);
        if (immediateBlockResDto == null || immediateBlockResDto.getBlockEndAt() == null) {
            return false;
        }
        return !now.isAfter(immediateBlockResDto.getBlockEndAt());
    }

    /**
     * repeat block 정책의 요일/시간대 차단 여부를 확인합니다.
     */
    private boolean isRepeatBlocked(Long lineId, LocalTime nowTime, DayOfWeek nowDayOfWeek) {
        List<RepeatBlockPolicyResDto> repeatBlocks = repeatBlockMapper.selectRepeatBlocksByLineId(lineId);
        if (repeatBlocks == null || repeatBlocks.isEmpty()) {
            return false;
        }

        for (RepeatBlockPolicyResDto repeatBlock : repeatBlocks) {
            if (repeatBlock == null || !Boolean.TRUE.equals(repeatBlock.getIsActive())) {
                continue;
            }

            List<RepeatBlockDayResDto> days = repeatBlock.getDays();
            if (days == null || days.isEmpty()) {
                continue;
            }

            for (RepeatBlockDayResDto day : days) {
                if (day == null || day.getDayOfWeek() != nowDayOfWeek) {
                    continue;
                }
                if (day.getStartAt() == null || day.getEndAt() == null) {
                    continue;
                }

                boolean inRange = !nowTime.isBefore(day.getStartAt()) && !nowTime.isAfter(day.getEndAt());
                if (inRange) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * policy_id별 활성화 상태를 조회합니다.
     */
    private Map<Integer, Boolean> loadPolicyActivation() {
        List<PolicyActivationSnapshotResDto> snapshots = policyBackOfficeMapper.selectPolicyActivationSnapshot();
        Map<Integer, Boolean> activationMap = new HashMap<>();
        if (snapshots == null || snapshots.isEmpty()) {
            return activationMap;
        }

        for (PolicyActivationSnapshotResDto snapshot : snapshots) {
            if (snapshot == null || snapshot.getPolicyId() == null) {
                continue;
            }
            activationMap.put(snapshot.getPolicyId(), Boolean.TRUE.equals(snapshot.getIsActive()));
        }
        return activationMap;
    }

    private boolean isPolicyEnabled(Map<Integer, Boolean> policyActivation, int policyId) {
        return Boolean.TRUE.equals(policyActivation.get(policyId));
    }

    private boolean isWhitelistBypass(Map<Integer, Boolean> policyActivation, AppPolicy appPolicy) {
        if (!isPolicyEnabled(policyActivation, POLICY_APP_WHITELIST_ID)) {
            return false;
        }
        return appPolicy != null
                && Boolean.TRUE.equals(appPolicy.getIsActive())
                && Boolean.TRUE.equals(appPolicy.getIsWhitelist());
    }

    private long resolveDailyLimit(LineLimit lineLimit) {
        if (lineLimit == null || !Boolean.TRUE.equals(lineLimit.getIsDailyLimitActive())) {
            return -1L;
        }
        Long limit = lineLimit.getDailyDataLimit();
        if (limit == null) {
            return -1L;
        }
        return limit;
    }

    private long resolveSharedMonthlyLimit(LineLimit lineLimit) {
        if (lineLimit == null || !Boolean.TRUE.equals(lineLimit.getIsSharedLimitActive())) {
            return -1L;
        }
        Long limit = lineLimit.getSharedDataLimit();
        if (limit == null) {
            return -1L;
        }
        return limit;
    }

    private long resolveAppDailyLimit(AppPolicy appPolicy) {
        if (appPolicy == null || !Boolean.TRUE.equals(appPolicy.getIsActive())) {
            return -1L;
        }
        Long limit = appPolicy.getDataLimit();
        if (limit == null) {
            return -1L;
        }
        return limit;
    }

    private int deductRemainingAmount(TrafficPoolType poolType, TrafficPayloadReqDto payload, long deductAmount) {
        if (deductAmount <= 0 || payload == null) {
            return 0;
        }

        return switch (poolType) {
            case INDIVIDUAL -> payload.getLineId() == null
                    ? 0
                    : trafficRefillSourceMapper.deductIndividualRemaining(payload.getLineId(), deductAmount);
            case SHARED -> payload.getFamilyId() == null
                    ? 0
                    : trafficRefillSourceMapper.deductSharedRemaining(payload.getFamilyId(), deductAmount);
        };
    }

    private long selectRemainingAmountForUpdate(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        if (payload == null) {
            return 0L;
        }

        Long rawRemaining = switch (poolType) {
            case INDIVIDUAL -> payload.getLineId() == null
                    ? null
                    : trafficRefillSourceMapper.selectIndividualRemainingForUpdate(payload.getLineId());
            case SHARED -> payload.getFamilyId() == null
                    ? null
                    : trafficRefillSourceMapper.selectSharedRemainingForUpdate(payload.getFamilyId());
        };

        return normalizeNonNegative(rawRemaining);
    }

    private long selectRemainingAmount(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        if (payload == null) {
            return 0L;
        }

        Long rawRemaining = switch (poolType) {
            case INDIVIDUAL -> payload.getLineId() == null
                    ? null
                    : trafficRefillSourceMapper.selectIndividualRemaining(payload.getLineId());
            case SHARED -> payload.getFamilyId() == null
                    ? null
                    : trafficRefillSourceMapper.selectSharedRemaining(payload.getFamilyId());
        };
        return normalizeNonNegative(rawRemaining);
    }

    private boolean isPayloadValid(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        if (poolType == null || payload == null) {
            return false;
        }
        if (payload.getLineId() == null || payload.getLineId() <= 0) {
            return false;
        }
        if (payload.getAppId() == null || payload.getAppId() < 0) {
            return false;
        }

        return switch (poolType) {
            case INDIVIDUAL -> true;
            case SHARED -> payload.getFamilyId() != null && payload.getFamilyId() > 0;
        };
    }

    private DayOfWeek toLuaDayOfWeek(LocalDateTime now) {
        int luaDayNum = now.getDayOfWeek().getValue() % 7;
        return switch (luaDayNum) {
            case 0 -> DayOfWeek.SUN;
            case 1 -> DayOfWeek.MON;
            case 2 -> DayOfWeek.TUE;
            case 3 -> DayOfWeek.WED;
            case 4 -> DayOfWeek.THU;
            case 5 -> DayOfWeek.FRI;
            default -> DayOfWeek.SAT;
        };
    }

    private long normalizeNonNegative(Long value) {
        if (value == null || value <= 0) {
            return 0L;
        }
        return value;
    }

    private TrafficLuaExecutionResult buildResult(long answer, TrafficLuaStatus status) {
        return TrafficLuaExecutionResult.builder()
                .answer(answer)
                .status(status)
                .build();
    }
}
