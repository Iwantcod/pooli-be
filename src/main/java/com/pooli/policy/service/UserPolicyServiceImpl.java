package com.pooli.policy.service;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.permission.mapper.FamilyLineMapper;
import com.pooli.policy.domain.dto.request.*;
import com.pooli.policy.domain.dto.response.*;
import com.pooli.policy.domain.entity.DailyLimit;
import com.pooli.policy.domain.entity.SharedLimit;
import com.pooli.policy.exception.PolicyErrorCode;
import com.pooli.policy.mapper.DailyLimitMapper;
import com.pooli.policy.mapper.SharedLimitMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserPolicyServiceImpl implements UserPolicyService {
    private final FamilyLineMapper familyLineMapper;
    private final DailyLimitMapper dailyLimitMapper;
    private final SharedLimitMapper sharedLimitMapper;

    @Override
    public List<ActivePolicyResDto> getActivePolicies() {
        return List.of();
    }

    @Override
    public List<RepeatBlockPolicyResDto> getRepeatBlockPolicies(Long lineId, AuthUserDetails auth) {
        return List.of();
    }

    @Override
    public RepeatBlockPolicyResDto createRepeatBlockPolicy(RepeatBlockPolicyReqDto request, AuthUserDetails auth) {
        return null;
    }

    @Override
    public RepeatBlockPolicyResDto updateRepeatBlockPolicy(Long repeatBlockId, RepeatBlockPolicyReqDto request, AuthUserDetails auth) {
        return null;
    }

    @Override
    public RepeatBlockPolicyResDto deleteRepeatBlockPolicy(Long repeatBlockId, AuthUserDetails auth) {
        return null;
    }

    @Override
    public ImmediateBlockResDto getImmediateBlockPolicy(Long lineId, AuthUserDetails auth) {
        return null;
    }

    @Override
    public ImmediateBlockResDto updateImmediateBlockPolicy(Long lineId, ImmediateBlockReqDto request, AuthUserDetails auth) {
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public LimitPolicyResDto getLimitPolicy(Long lineId, AuthUserDetails auth) {
        // 1. 조회 대상 lineId가 속한 가족그룹과, 요청을 보낸 사용자의 가족 그룹이 일치하는지 검증
        checkIsSameFamilyGroup(lineId, auth.getLineId());

        // 2. 두 종류의 제한 정보 테이블에서 정보 조회
        Optional<DailyLimit> dailyLimit = dailyLimitMapper.getExistDailyLimitByLineId(lineId);
        Optional<SharedLimit> sharedLimit = sharedLimitMapper.getExistSharedLimitByLineId(lineId);

        LimitPolicyResDto answer = LimitPolicyResDto.builder()
                .dailyLimitId((dailyLimit.isPresent()) ? dailyLimit.get().getDailyLimitId() : null)
                .dailyDataLimit((dailyLimit.isPresent()) ? dailyLimit.get().getDailyDataLimit() : null)
                .isDailyDataLimitActive((dailyLimit.isPresent()) ? dailyLimit.get().getIsActive() : null)
                .sharedLimitId((sharedLimit.isPresent()) ? sharedLimit.get().getSharedLimitId() : null)
                .sharedDataLimit((sharedLimit.isPresent()) ? sharedLimit.get().getSharedDataLimit() : null)
                .isSharedDataLimitActive((sharedLimit.isPresent()) ? sharedLimit.get().getIsActive() : null)
                .build();

        // 3. ResDto 조립 후 return
        return answer;
    }

    @Override
    @Transactional
    public LimitPolicyResDto toggleDailyTotalLimitPolicy(Long lineId, AuthUserDetails auth) {
        // 1. 조회 대상 lineId가 속한 가족그룹과, 요청을 보낸 사용자의 가족 그룹이 일치하는지 검증
        checkIsSameFamilyGroup(lineId, auth.getLineId());

        // 2. 제한 정책 존재 여부 조회
        Optional<DailyLimit> dailyLimit = dailyLimitMapper.getExistDailyLimitByLineId(lineId);
        if (dailyLimit.isPresent()) {
            // 삭제 상태가 아닌 dailyLimit 레코드가 존재하는 경우
            if(dailyLimit.get().getIsActive()) {
                // 제한 정책이 활성화된 경우 비활성화
                int def = dailyLimitMapper.deactivateDailyLimitByDailyLimitId(dailyLimit.get().getDailyLimitId());
                if(def != 1) {
                    throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
                }
                return LimitPolicyResDto.builder()
                        .dailyLimitId(dailyLimit.get().getDailyLimitId())
                        .dailyDataLimit(dailyLimit.get().getDailyDataLimit())
                        .isDailyDataLimitActive(false)
                        .build();
            } else {
                // 비활성화된 경우 재활성화
                int def = dailyLimitMapper.activateDailyLimitByDailyLimitId(dailyLimit.get().getDailyLimitId());
                if(def != 1) {
                    throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
                }
                return LimitPolicyResDto.builder()
                        .dailyLimitId(dailyLimit.get().getDailyLimitId())
                        .dailyDataLimit(dailyLimit.get().getDailyDataLimit())
                        .isDailyDataLimitActive(true)
                        .build();
            }
        } else {
            // dailyLimit 레코드가 없거나, 삭제 상태인 경우 새 레코드 insert
            DailyLimit newDailyLimit = DailyLimit.builder()
                    .lineId(lineId)
                    .dailyDataLimit(-1L) // 기본값: 무제한
                    .isActive(true) // 기본값: 활성화 상태
                    .build();
            int def = dailyLimitMapper.createDailyLimit(newDailyLimit);
            if(def != 1) {
                throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
            }
            return LimitPolicyResDto.builder()
                    .dailyLimitId(newDailyLimit.getDailyLimitId())
                    .dailyDataLimit(newDailyLimit.getDailyDataLimit())
                    .isDailyDataLimitActive(newDailyLimit.getIsActive())
                    .build();
        }
    }

    @Override
    public LimitPolicyResDto updateDailyTotalLimitPolicyValue(LimitPolicyUpdateReqDto request, AuthUserDetails auth) {
        // 1. 대상 dailyLimit 레코드 조회
        Optional<DailyLimit> dailyLimit = dailyLimitMapper.getExistDailyLimitById(request.getLimitPolicyId());
        if(dailyLimit.isEmpty()) {
            throw new ApplicationException(PolicyErrorCode.LIMIT_POLICY_NOT_FOUND);
        }

        // 2. 대상 lineId가 동일 가족에 속했는지 검증
        checkIsSameFamilyGroup(dailyLimit.get().getLineId(), auth.getLineId());

        // 3. update 진행
        int def = dailyLimitMapper.updateDailyDataLimit(request);
        if(def != 1) {
            throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
        }

        return LimitPolicyResDto.builder()
                .dailyLimitId(dailyLimit.get().getDailyLimitId())
                .dailyDataLimit(request.getPolicyValue())
                .isDailyDataLimitActive(dailyLimit.get().getIsActive())
                .build();
    }

    @Override
    public LimitPolicyResDto toggleSharedPoolLimitPolicy(Long lineId, AuthUserDetails auth) {
        // 1. 조회 대상 lineId가 속한 가족그룹과, 요청을 보낸 사용자의 가족 그룹이 일치하는지 검증
        checkIsSameFamilyGroup(lineId, auth.getLineId());

        // 2. 제한 정책 존재 여부 조회
        Optional<SharedLimit> sharedLimit = sharedLimitMapper.getExistSharedLimitByLineId(lineId);
        if (sharedLimit.isPresent()) {
            // 삭제 상태가 아닌 sharedLimit 레코드가 존재하는 경우
            if(sharedLimit.get().getIsActive()) {
                // 제한 정책이 활성화된 경우 비활성화
                int def = sharedLimitMapper.deactivateSharedLimitBySharedLimitId(sharedLimit.get().getSharedLimitId());
                if(def != 1) {
                    throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
                }
                return LimitPolicyResDto.builder()
                        .dailyLimitId(sharedLimit.get().getSharedLimitId())
                        .dailyDataLimit(sharedLimit.get().getSharedDataLimit())
                        .isDailyDataLimitActive(false)
                        .build();
            } else {
                // 비활성화된 경우 재활성화
                int def = sharedLimitMapper.activateSharedLimitBySharedLimitId(sharedLimit.get().getSharedLimitId());
                if(def != 1) {
                    throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
                }
                return LimitPolicyResDto.builder()
                        .dailyLimitId(sharedLimit.get().getSharedLimitId())
                        .dailyDataLimit(sharedLimit.get().getSharedDataLimit())
                        .isDailyDataLimitActive(true)
                        .build();
            }
        } else {
            // sharedLimit 레코드가 없거나, 삭제 상태인 경우 새 레코드 insert
            SharedLimit newSharedLimit = SharedLimit.builder()
                    .lineId(lineId)
                    .sharedDataLimit(-1L)  // 기본값: 무제한
                    .isActive(true)         // 기본값: 활성화 상태
                    .build();
            int def = sharedLimitMapper.createSharedLimit(newSharedLimit);
            if(def != 1) {
                throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
            }
            return LimitPolicyResDto.builder()
                    .sharedLimitId(newSharedLimit.getSharedLimitId())
                    .sharedDataLimit(newSharedLimit.getSharedDataLimit())
                    .isSharedDataLimitActive(newSharedLimit.getIsActive())
                    .build();
        }
    }

    @Override
    public LimitPolicyResDto updateSharedPoolLimitPolicyValue(LimitPolicyUpdateReqDto request, AuthUserDetails auth) {
        // 1. 대상 sharedLimit 레코드 조회
        Optional<SharedLimit> sharedLimit = sharedLimitMapper.getExistSharedLimitById(request.getLimitPolicyId());
        if (sharedLimit.isEmpty()) {
            throw new ApplicationException(PolicyErrorCode.LIMIT_POLICY_NOT_FOUND);
        }

        // 2. 대상 lineId가 동일 가족에 속했는지 검증
        checkIsSameFamilyGroup(sharedLimit.get().getLineId(), auth.getLineId());

        // 3. update 진행
        int affectedRows = sharedLimitMapper.updateSharedDataLimit(request);
        if (affectedRows != 1) {
            throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
        }

        return LimitPolicyResDto.builder()
                .sharedLimitId(sharedLimit.get().getSharedLimitId())
                .sharedDataLimit(request.getPolicyValue())
                .isSharedDataLimitActive(sharedLimit.get().getIsActive())
                .build();
    }

    @Override
    public List<AppPolicyResDto> getAppPolicies(Long lineId, AuthUserDetails auth, Integer pageNumber, Integer pageSize) {
        return List.of();
    }

    @Override
    public AppPolicyResDto createAppPolicy(AppPolicyCreateReqDto request, AuthUserDetails auth) {
        return null;
    }

    @Override
    public AppPolicyResDto updateAppDataLimit(AppDataLimitUpdateReqDto request, AuthUserDetails auth) {
        return null;
    }

    @Override
    public AppPolicyResDto updateAppSpeedLimit(AppSpeedLimitUpdateReqDto request, AuthUserDetails auth) {
        return null;
    }

    @Override
    public void toggleAppPolicyActive(Long appPolicyId, AuthUserDetails auth) {

    }

    @Override
    public void deleteAppPolicy(Long appPolicyId, AuthUserDetails auth) {

    }

    @Override
    public AppliedPolicyResDto getAppliedPolicies(Long lineId, AuthUserDetails auth) {
        return null;
    }

    /**
     * 대상 lineId가 API 요청자와 동일한 가족 그룹에 속해있는지 검증
     * @param targetLineId 대상 lineId
     * @param myLineId API 요청자 lineId
     * @throws ApplicationException '접근 권한 없음' 예외 throws
     */
    private void checkIsSameFamilyGroup(Long targetLineId, Long myLineId) throws ApplicationException {
        List<Long> myFamilyLineIdList = familyLineMapper.findAllFamilyIdByLineId(myLineId);

        if(!myFamilyLineIdList.contains(targetLineId)){
            // 동일한 가족 그룹에 속해있지 않다면 권한 없음을 의미
            throw new ApplicationException(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN);
        }
    }
}
