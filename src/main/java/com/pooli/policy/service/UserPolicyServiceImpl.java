package com.pooli.policy.service;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.policy.domain.dto.request.*;
import com.pooli.policy.domain.dto.response.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class UserPolicyServiceImpl implements UserPolicyService {
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
        return null;
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
}
