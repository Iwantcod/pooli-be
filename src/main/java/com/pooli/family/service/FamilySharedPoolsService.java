package com.pooli.family.service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.family.exception.SharedPoolErrorCode;

import com.pooli.family.domain.dto.request.UpdateSharedDataThresholdReqDto;
import com.pooli.family.domain.dto.response.*;
import com.pooli.family.domain.entity.SharedPoolDomain;
import com.pooli.family.repository.FamilySharedPoolMapper;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.service.AlarmHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FamilySharedPoolsService {

    private final FamilySharedPoolMapper sharedPoolMapper;
    private final AlarmHistoryService alarmHistoryService;

    // 세션의 lineId로 familyId를 DB에서 조회하는 헬퍼 메서드
    public Long getFamilyIdByLineId(Long lineId) {
        Long familyId = sharedPoolMapper.selectFamilyIdByLineId(lineId);
        if (familyId == null) {
            throw new ApplicationException(SharedPoolErrorCode.NOT_FAMILY_MEMBER);
        }
        return familyId;
    }

    public SharedPoolMyStatusResDto getMySharedPoolStatus(Long lineId) {
        SharedPoolDomain domain = sharedPoolMapper.selectMySharedPoolStatus(lineId);
        
        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        return SharedPoolMyStatusResDto.builder()
                .remainingData(domain.getRemainingData())
                .contributionAmount(domain.getPersonalContribution())
                .build();
    }

    public FamilySharedPoolResDto getFamilySharedPool(Long familyId) {
        SharedPoolDomain domain = sharedPoolMapper.selectFamilySharedPool(familyId);

        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        return FamilySharedPoolResDto.builder()
                .poolTotalData(domain.getPoolTotalData())
                .poolRemainingData(domain.getPoolRemainingData())
                .poolBaseData(domain.getPoolBaseData())
                .monthlyUsageAmount(domain.getMonthlyUsageAmount())
                .monthlyContributionAmount(domain.getMonthlyContributionAmount())
                .build();
    }

    // 트랜잭션 필수: 여러 DB UPDATE 작업을 묶음
    @Transactional
    public void contributeToSharedPool(Long lineId, Long familyId, Long amount) {

        // 검증: 잔여 데이터가 충전량보다 충분한지 확인
        Long remainingData = sharedPoolMapper.selectRemainingData(lineId);
        if (remainingData == null) {
            throw new ApplicationException(SharedPoolErrorCode.LINE_NOT_FOUND);
        }
        if (remainingData < amount) {
            throw new ApplicationException(SharedPoolErrorCode.INSUFFICIENT_DATA);
        }

        // 1. 개인 데이터 잔여량 차감
        sharedPoolMapper.updateLineRemainingData(lineId, amount);

        // 2. 가족 공유풀 총량 및 잔여량 증가
        sharedPoolMapper.updateFamilyPoolData(familyId, amount);

        // 3. 충전 이력 기록 (당월 데이터 통합을 위해 DUPLICATE 처리됨)
        sharedPoolMapper.insertContribution(familyId, lineId, amount);

        // 4. 알람: 가족 전체 (본인 제외)에게 데이터 담기 알림
        sendAlarmToFamily(familyId, lineId, AlarmType.SHARED_POOL_CONTRIBUTION,
                Map.of("amount", String.valueOf(amount)));
    }

    public SharedPoolDetailResDto getSharedPoolDetail(Long familyId, Long lineId) {
        SharedPoolDomain domain = sharedPoolMapper.selectSharedPoolDetail(familyId, lineId);

        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        // 정책으로 제한된 공유풀 데이터 한도 조회 (SHARED_LIMIT 테이블)
        Long sharedDataLimit = sharedPoolMapper.selectSharedDataLimit(lineId);

        // 사용 가능한 공유풀 데이터 총량 = min(전체 공유풀 총량, 정책 제한 한도)
        Long poolTotal = domain.getPoolTotalData();
        Long effectiveTotal = (sharedDataLimit != null) ? Math.min(poolTotal, sharedDataLimit) : poolTotal;

        // 사용 가능한 공유풀 데이터 잔량 = min(개인 제한량 잔여, 전체 공유풀 잔여)
        Long poolRemaining = domain.getPoolRemainingData();
        Long effectiveRemaining = (sharedDataLimit != null) ? Math.min(poolRemaining, sharedDataLimit) : poolRemaining;

        return SharedPoolDetailResDto.builder()
                .basicDataAmount(domain.getBasicDataAmount())
                .remainingDataAmount(domain.getRemainingData())
                .sharedPoolTotalAmount(effectiveTotal)
                .sharedPoolRemainingAmount(effectiveRemaining)
                .build();
    }

    public SharedPoolMainResDto getSharedPoolMain(Long familyId) {
        SharedPoolDomain domain = sharedPoolMapper.selectSharedPoolMain(familyId);

        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        return SharedPoolMainResDto.builder()
                .sharedPoolBaseData(domain.getPoolBaseData())
                .sharedPoolAdditionalData(domain.getMonthlyContributionAmount()) // 당월 충전량이 추가 분을 의미함
                .sharedPoolRemainingData(domain.getPoolRemainingData())
                .sharedPoolTotalData(domain.getPoolTotalData())
                .build();
    }

    public SharedDataThresholdResDto getSharedDataThreshold(Long familyId) {
        SharedPoolDomain domain = sharedPoolMapper.selectSharedDataThreshold(familyId);

        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        return SharedDataThresholdResDto.builder()
                .isThresholdActive(domain.getIsThresholdActive())
                .familyThreshold(domain.getFamilyThreshold())
                .build();
    }

    public void updateSharedDataThreshold(Long familyId, UpdateSharedDataThresholdReqDto request) {
        sharedPoolMapper.updateSharedDataThreshold(familyId, request.getNewFamilyThreshold());

        // 알람: 가족 전체에게 임계치 변경 알림
        sendAlarmToFamily(familyId, null, AlarmType.SHARED_POOL_THRESHOLD_CHANGE,
                Map.of("newThreshold", String.valueOf(request.getNewFamilyThreshold())));
    }

    /**
     * 가족 구성원 전체에게 알람을 전송하는 헬퍼 메서드
     * @param excludeLineId 본인 제외 (null이면 전체에게 보냄)
     */
    private void sendAlarmToFamily(Long familyId, Long excludeLineId, AlarmType alarmType, Map<String, String> values) {
        List<Long> familyLineIds = sharedPoolMapper.selectLineIdsByFamilyId(familyId);
        for (Long targetLineId : familyLineIds) {
            if (excludeLineId != null && targetLineId.equals(excludeLineId)) {
                continue;
            }
            alarmHistoryService.createAlarm(targetLineId, AlarmCode.FAMILY, alarmType, values);
        }
    }
}
