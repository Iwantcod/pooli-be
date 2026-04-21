package com.pooli.traffic.service.decision;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.policy.domain.dto.response.ImmediateBlockResDto;
import com.pooli.policy.domain.dto.response.PolicyActivationSnapshotResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockDayResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import com.pooli.policy.domain.entity.AppPolicy;
import com.pooli.policy.domain.enums.DayOfWeek;
import com.pooli.policy.mapper.AppPolicyMapper;
import com.pooli.policy.mapper.ImmediateBlockMapper;
import com.pooli.policy.mapper.PolicyBackOfficeMapper;
import com.pooli.policy.mapper.RepeatBlockMapper;
import com.pooli.traffic.domain.TrafficDeductExecutionContext;
import com.pooli.traffic.domain.TrafficLuaExecutionResult;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.mapper.TrafficRefillSourceMapper;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

import lombok.RequiredArgsConstructor;

/**
 * Redis 정책 검증 장애 시 DB에서 차단성 정책만 판정해 차감을 이어가는 fallback 서비스입니다.
 * DB fallback 경로에서는 비차단 정책(일일/월간/앱 일일/앱 속도/QoS)을 적용하지 않습니다.
 * 화이트리스트 우회는 policy activation(정책 7) + app_policy(lineId, appId) 조합으로 판정합니다.
 */
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficDbDeductFallbackService {

    private static final int POLICY_REPEAT_BLOCK_ID = 1;
    private static final int POLICY_IMMEDIATE_BLOCK_ID = 2;
    private static final int POLICY_APP_WHITELIST_ID = 7;

    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final PolicyBackOfficeMapper policyBackOfficeMapper;
    private final AppPolicyMapper appPolicyMapper;
    private final ImmediateBlockMapper immediateBlockMapper;
    private final RepeatBlockMapper repeatBlockMapper;
    private final TrafficRefillSourceMapper trafficRefillSourceMapper;

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
        // fallback 진입 시점에도 입력 자체가 비정상이면 더 진행하지 않고 ERROR로 종료합니다.
        if (!isPayloadValid(poolType, payload)) {
            return buildResult(-1L, TrafficLuaStatus.ERROR);
        }

        // 정책 판정에 사용할 현재 시각(서비스 표준 timezone)을 고정합니다.
        LocalDateTime now = LocalDateTime.now(trafficRedisRuntimePolicy.zoneId());

        // 차단성 정책 3종(즉시/반복/화이트리스트) 판정을 위해
        // 정책 활성화 스냅샷 + app_policy(lineId, appId)를 함께 조회합니다.
        Map<Integer, Boolean> policyActivation = loadPolicyActivation();
        AppPolicy appPolicy = appPolicyMapper.findEntityExistByLineIdAndAppId(payload.getLineId(), payload.getAppId())
                .orElse(null);

        // 화이트리스트 우회가 활성화된 경우 즉시/반복 차단을 건너뜁니다.
        boolean whitelistBypass = isWhitelistBypass(policyActivation, appPolicy);

        if (!whitelistBypass) {
            // 1) 즉시 차단 정책: blockEndAt 이전이면 즉시 BLOCKED_IMMEDIATE 반환
            if (isPolicyEnabled(policyActivation, POLICY_IMMEDIATE_BLOCK_ID)
                    && isImmediateBlocked(payload.getLineId(), now)) {
                return buildResult(0L, TrafficLuaStatus.BLOCKED_IMMEDIATE);
            }

            // 2) 반복 차단 정책: 요일/시간대 규칙에 걸리면 BLOCKED_REPEAT 반환
            DayOfWeek nowDayOfWeek = toLuaDayOfWeek(now);
            DayOfWeek yesterdayDayOfWeek = previousLuaDayOfWeek(nowDayOfWeek);
            if (isPolicyEnabled(policyActivation, POLICY_REPEAT_BLOCK_ID)
                    && isRepeatBlocked(payload.getLineId(), now.toLocalTime(), nowDayOfWeek, yesterdayDayOfWeek)) {
                return buildResult(0L, TrafficLuaStatus.BLOCKED_REPEAT);
            }
        }

        // DB 행 잠금(for update)으로 현재 잔량을 읽고, 이번 요청량과 비교해 실제 차감량(answer)을 결정합니다.
        long currentAmount = selectRemainingAmountForUpdate(poolType, payload);
        long normalizedRequestedBytes = Math.max(0L, requestedBytes);
        long answer = Math.min(currentAmount, normalizedRequestedBytes);
        boolean insufficientBalance = currentAmount < normalizedRequestedBytes;
        TrafficLuaStatus finalStatus = TrafficLuaStatus.OK;
        if (insufficientBalance) {
            finalStatus = TrafficLuaStatus.NO_BALANCE;
        }
        if (answer <= 0) {
            // answer==0은 잔량 부족 성격이므로 NO_BALANCE로 리필 경로를 연다.
            return buildResult(0L, TrafficLuaStatus.NO_BALANCE);
        }

        // 1차 UPDATE 시도: 동시성 경합으로 업데이트 실패할 수 있어 재조회 기반 1회 보정 재시도를 수행합니다.
        int updatedRows = deductRemainingAmount(poolType, payload, answer);
        if (updatedRows <= 0) {
            // 경합 후 최신 잔량을 다시 읽어 같은 규칙으로 재계산합니다.
            long reloadedAmount = selectRemainingAmount(poolType, payload);
            long reloadedAnswer = Math.min(reloadedAmount, normalizedRequestedBytes);
            boolean reloadedInsufficientBalance = reloadedAmount < normalizedRequestedBytes;
            TrafficLuaStatus reloadedStatus = TrafficLuaStatus.OK;
            if (reloadedInsufficientBalance) {
                reloadedStatus = TrafficLuaStatus.NO_BALANCE;
            }
            if (reloadedAnswer <= 0) {
                return buildResult(0L, TrafficLuaStatus.NO_BALANCE);
            }

            // 보정 재시도 UPDATE도 실패하면 이번 요청은 차감하지 못한 것으로 판단하고 상태만 반환합니다.
            updatedRows = deductRemainingAmount(poolType, payload, reloadedAnswer);
            if (updatedRows <= 0) {
                return buildResult(0L, reloadedStatus);
            }
            answer = reloadedAnswer;
            finalStatus = reloadedStatus;
        }

        return buildResult(answer, finalStatus);
    }

    /**
     * line 기준 즉시 차단 활성 상태를 확인합니다.
     */
    private boolean isImmediateBlocked(Long lineId, LocalDateTime now) {
        // 즉시 차단 레코드가 없으면 차단 아님으로 처리합니다.
        ImmediateBlockResDto immediateBlockResDto = immediateBlockMapper.selectImmediateBlockPolicy(lineId);
        if (immediateBlockResDto == null || immediateBlockResDto.getBlockEndAt() == null) {
            return false;
        }
        // 현재 시각이 block 종료시각 이전(또는 동일)인 동안 차단입니다.
        return !now.isAfter(immediateBlockResDto.getBlockEndAt());
    }

    /**
     * repeat block 정책의 요일/시간대 차단 여부를 확인합니다.
     */
    private boolean isRepeatBlocked(
            Long lineId,
            LocalTime nowTime,
            DayOfWeek nowDayOfWeek,
            DayOfWeek yesterdayDayOfWeek
    ) {
        // 반복 차단 정책이 없으면 즉시 false를 반환합니다.
        List<RepeatBlockPolicyResDto> repeatBlocks = repeatBlockMapper.selectRepeatBlocksByLineId(lineId);
        if (repeatBlocks == null || repeatBlocks.isEmpty()) {
            return false;
        }

        // 활성화된 반복 차단 정책만 순회합니다.
        for (RepeatBlockPolicyResDto repeatBlock : repeatBlocks) {
            if (repeatBlock == null || !Boolean.TRUE.equals(repeatBlock.getIsActive())) {
                continue;
            }

            // 요일/시간대 세부 규칙이 없는 정책은 무시합니다.
            List<RepeatBlockDayResDto> days = repeatBlock.getDays();
            if (days == null || days.isEmpty()) {
                continue;
            }

            // 각 요일 규칙을 현재 시각에 대입해 차단 여부를 판정합니다.
            for (RepeatBlockDayResDto day : days) {
                if (day == null || day.getDayOfWeek() == null) {
                    continue;
                }
                if (day.getStartAt() == null || day.getEndAt() == null) {
                    continue;
                }

                LocalTime startAt = day.getStartAt();
                LocalTime endAt = day.getEndAt();

                if (!startAt.isAfter(endAt)) {
                    // 일반 구간(start <= end): 같은 요일에서만 단순 범위 비교
                    if (day.getDayOfWeek() != nowDayOfWeek) {
                        continue;
                    }
                    boolean inRange = !nowTime.isBefore(startAt) && !nowTime.isAfter(endAt);
                    if (inRange) {
                        return true;
                    }
                    continue;
                }

                // 자정 넘김 구간(start > end)은 당일 밤 구간과 익일 새벽 구간으로 나눠 판정한다.
                boolean isTodaySegmentBlocked = day.getDayOfWeek() == nowDayOfWeek
                        && !nowTime.isBefore(startAt);
                boolean isNextDaySegmentBlocked = day.getDayOfWeek() == yesterdayDayOfWeek
                        && !nowTime.isAfter(endAt);
                if (isTodaySegmentBlocked || isNextDaySegmentBlocked) {
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
        // policy_id -> is_active 맵을 만들어 이후 분기에서 O(1)로 조회합니다.
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
        // 누락된 키는 false로 취급합니다.
        return Boolean.TRUE.equals(policyActivation.get(policyId));
    }

    private boolean isWhitelistBypass(Map<Integer, Boolean> policyActivation, AppPolicy appPolicy) {
        // 화이트리스트 정책이 꺼져 있으면 우회 자체를 허용하지 않습니다.
        if (!isPolicyEnabled(policyActivation, POLICY_APP_WHITELIST_ID)) {
            return false;
        }
        // 앱 정책이 활성 + whitelist=true인 경우에만 우회합니다.
        return appPolicy != null
                && Boolean.TRUE.equals(appPolicy.getIsActive())
                && Boolean.TRUE.equals(appPolicy.getIsWhitelist());
    }

    private int deductRemainingAmount(TrafficPoolType poolType, TrafficPayloadReqDto payload, long deductAmount) {
        // 비정상 입력은 DB 업데이트를 시도하지 않고 0행 처리로 간주합니다.
        if (deductAmount <= 0 || payload == null) {
            return 0;
        }

        // 풀 타입에 따라 서로 다른 잔량 테이블 업데이트 경로를 선택합니다.
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
        // FOR UPDATE 조회는 차감 직전 경합 제어를 위해 사용합니다.
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

        // null/음수 값을 방어해 후속 계산이 음수로 흐르지 않게 합니다.
        return normalizeNonNegative(rawRemaining);
    }

    private long selectRemainingAmount(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        // 재조회 경로는 잠금 없이 최신 스냅샷을 다시 읽기 위한 용도입니다.
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
        // null/음수 값을 방어해 후속 계산이 음수로 흐르지 않게 합니다.
        return normalizeNonNegative(rawRemaining);
    }

    private boolean isPayloadValid(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        // 공통 필수값(풀 타입, payload, lineId, appId)을 먼저 검증합니다.
        if (poolType == null || payload == null) {
            return false;
        }
        if (payload.getLineId() == null || payload.getLineId() <= 0) {
            return false;
        }
        if (payload.getAppId() == null || payload.getAppId() < 0) {
            return false;
        }

        // SHARED는 familyId가 추가 필수, INDIVIDUAL은 lineId만 있으면 충분합니다.
        return switch (poolType) {
            case INDIVIDUAL -> true;
            case SHARED -> payload.getFamilyId() != null && payload.getFamilyId() > 0;
        };
    }

    private DayOfWeek toLuaDayOfWeek(LocalDateTime now) {
        // Java(MON=1..SUN=7) -> Lua 스크립트 관례(SUN=0..SAT=6)로 변환 후 enum으로 매핑합니다.
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

    /**
     * Lua day_num 체계(SUN=0) 기준으로 전일 요일을 계산합니다.
     */
    private DayOfWeek previousLuaDayOfWeek(DayOfWeek nowDayOfWeek) {
        if (nowDayOfWeek == null) {
            return null;
        }
        DayOfWeek[] values = DayOfWeek.values();
        int previousIndex = (nowDayOfWeek.ordinal() + values.length - 1) % values.length;
        return values[previousIndex];
    }

    private long normalizeNonNegative(Long value) {
        // DB null/음수 값을 안전한 0으로 정규화합니다.
        if (value == null || value <= 0) {
            return 0L;
        }
        return value;
    }

    private TrafficLuaExecutionResult buildResult(long answer, TrafficLuaStatus status) {
        // DB fallback에서도 Lua 결과 계약(answer/status)을 동일하게 유지합니다.
        return TrafficLuaExecutionResult.builder()
                .answer(answer)
                .status(status)
                .build();
    }
}
