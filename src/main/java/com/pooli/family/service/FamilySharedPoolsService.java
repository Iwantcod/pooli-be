package com.pooli.family.service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.family.domain.dto.request.CreateSharedPoolContributionReqDto;
import com.pooli.family.domain.dto.request.UpdateSharedDataThresholdReqDto;
import com.pooli.family.domain.dto.response.*;
import com.pooli.family.domain.entity.SharedPoolDomain;
import com.pooli.family.repository.FamilySharedPoolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FamilySharedPoolsService {

    private final FamilySharedPoolMapper sharedPoolMapper;

    public SharedPoolMyStatusResDto getMySharedPoolStatus(Long lineId) {
        SharedPoolDomain domain = sharedPoolMapper.selectMySharedPoolStatus(lineId);
        
        if (domain == null) {
            throw new ApplicationException(CommonErrorCode.NOT_FOUND);
        }

        return SharedPoolMyStatusResDto.builder()
                .remainingData(domain.getRemainingData())
                .contributionAmount(domain.getPersonalContribution())
                .build();
    }

    public FamilySharedPoolResDto getFamilySharedPool(Long familyId) {
        SharedPoolDomain domain = sharedPoolMapper.selectFamilySharedPool(familyId);

        if (domain == null) {
            throw new ApplicationException(CommonErrorCode.NOT_FOUND);
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
    public void contributeToSharedPool(CreateSharedPoolContributionReqDto request) {
        Long amount = request.getAmount();
        Integer lineId = request.getLineId();
        Integer familyId = request.getFamilyId();

        // 검증 1: 해당 lineId가 familyId의 구성원인지 확인
        int memberCount = sharedPoolMapper.countFamilyLineMembership(Long.valueOf(familyId), Long.valueOf(lineId));
        if (memberCount == 0) {
            throw new ApplicationException(CommonErrorCode.INVALID_REQUEST, "해당 회선은 이 가족의 구성원이 아닙니다.");
        }

        // 검증 3: 잔여 데이터가 충전량보다 충분한지 확인
        Long remainingData = sharedPoolMapper.selectRemainingData(Long.valueOf(lineId));
        if (remainingData == null) {
            throw new ApplicationException(CommonErrorCode.NOT_FOUND, "회선 정보를 찾을 수 없습니다.");
        }
        if (remainingData < amount) {
            throw new ApplicationException(CommonErrorCode.INVALID_REQUEST, "잔여 데이터가 부족합니다.");
        }

        // 1. 개인 데이터 잔여량 차감
        sharedPoolMapper.updateLineRemainingData(Long.valueOf(lineId), amount);

        // 2. 가족 공유풀 총량 및 잔여량 증가
        sharedPoolMapper.updateFamilyPoolData(Long.valueOf(familyId), amount);

        // 3. 충전 이력 기록 (당월 데이터 통합을 위해 DUPLICATE 처리됨)
        sharedPoolMapper.insertContribution(Long.valueOf(familyId), Long.valueOf(lineId), amount);
    }

    public SharedPoolDetailResDto getSharedPoolDetail(Long familyId, Long lineId) {
        SharedPoolDomain domain = sharedPoolMapper.selectSharedPoolDetail(familyId, lineId);

        if (domain == null) {
            throw new ApplicationException(CommonErrorCode.NOT_FOUND);
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
            throw new ApplicationException(CommonErrorCode.NOT_FOUND);
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
            throw new ApplicationException(CommonErrorCode.NOT_FOUND);
        }

        return SharedDataThresholdResDto.builder()
                .isThresholdActive(domain.getIsThresholdActive())
                .familyThreshold(domain.getFamilyThreshold())
                .build();
    }

    public void updateSharedDataThreshold(Long familyId, UpdateSharedDataThresholdReqDto request) {
        // 권한 검증 등은 추후 추가 가능
        sharedPoolMapper.updateSharedDataThreshold(familyId, request.getNewFamilyThreshold());
    }
}
