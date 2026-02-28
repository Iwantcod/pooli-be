package com.pooli.policy.service;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.permission.mapper.FamilyLineMapper;
import com.pooli.policy.domain.dto.request.*;
import com.pooli.policy.domain.dto.response.*;
import com.pooli.policy.domain.entity.DailyLimit;
import com.pooli.policy.domain.entity.SharedLimit;
import com.pooli.policy.mapper.DailyLimitMapper;
import com.pooli.policy.mapper.SharedLimitMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    public LimitPolicyResDto getLimitPolicy(Long lineId, AuthUserDetails auth) {
        // 1. 조회 대상 lineId가 속한 가족그룹과, 요청을 보낸 사용자의 가족 그룹이 일치하는지 검증
        checkIsSameFamilyGroup(lineId, auth.getLineId());

        // 2. 두 종류의 제한 정보 테이블에서 정보 조회
        Optional<DailyLimit> dailyLimit = dailyLimitMapper.getDailyLimitByLineId(lineId);
        Optional<SharedLimit> sharedLimit = sharedLimitMapper.getSharedLimitByLineId(lineId);

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
    public LimitPolicyResDto toggleDailyTotalLimitPolicy(Long lineId, AuthUserDetails auth) {
        return null;
    }

    @Override
    public LimitPolicyResDto updateDailyTotalLimitPolicyValue(LimitPolicyUpdateReqDto request, AuthUserDetails auth) {
        return null;
    }

    @Override
    public LimitPolicyResDto toggleSharedPoolLimitPolicy(Long lineId, AuthUserDetails auth) {
        return null;
    }

    @Override
    public LimitPolicyResDto updateSharedPoolLimitPolicyValue(LimitPolicyUpdateReqDto request, AuthUserDetails auth) {
        return null;
    }

    @Override
    public List<AppPolicyResDto> getAppPolicies(Long lineId, AuthUserDetails auth) {
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
