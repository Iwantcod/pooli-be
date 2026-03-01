package com.pooli.policy.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.permission.mapper.FamilyLineMapper;
import com.pooli.policy.domain.dto.request.AppDataLimitUpdateReqDto;
import com.pooli.policy.domain.dto.request.AppPolicyCreateReqDto;
import com.pooli.policy.domain.dto.request.AppSpeedLimitUpdateReqDto;
import com.pooli.policy.domain.dto.request.ImmediateBlockReqDto;
import com.pooli.policy.domain.dto.request.LimitPolicyUpdateReqDto;
import com.pooli.policy.domain.dto.request.RepeatBlockPolicyReqDto;
import com.pooli.policy.domain.dto.response.ActivePolicyResDto;
import com.pooli.policy.domain.dto.response.AppPolicyResDto;
import com.pooli.policy.domain.dto.response.AppliedPolicyResDto;
import com.pooli.policy.domain.dto.response.ImmediateBlockResDto;
import com.pooli.policy.domain.dto.response.LimitPolicyResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockDayResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import com.pooli.policy.exception.PolicyErrorCode;
import com.pooli.policy.mapper.PolicyBackOfficeMapper;
import com.pooli.policy.mapper.RepeatBlockMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserPolicyServiceImpl implements UserPolicyService {

    private final PolicyBackOfficeMapper policyBackOfficeMapper;
    private final RepeatBlockMapper repeatBlockMapper;
    private final FamilyLineMapper familyLineMapper;
    
	@Override
	public List<ActivePolicyResDto> getActivePolicies() {
		
		return policyBackOfficeMapper.selectActivePolicies();
	}

	@Override
	public List<RepeatBlockPolicyResDto> getRepeatBlockPolicies(Long lineId, AuthUserDetails auth) {
		 // OWNER 검증(세션에서 확인)
	    boolean isOwner = auth.getRoleNames().contains("ROLE_FAM_OWNER");
	    if (!isOwner) {
	        throw new ApplicationException(CommonErrorCode.FAMILY_REPRESENTATIVE_FORBIDDEN);
	    }
	    
	    // lineId 존재 확인
	    familyLineMapper.findByLineId(lineId)
	        .orElseThrow(() -> new ApplicationException(PolicyErrorCode.LINE_NOT_FOUND));
	    
	    // 조회
	    return repeatBlockMapper.selectRepeatBlocksByLineId(lineId);
	}

	@Override
    @Transactional
	public RepeatBlockPolicyResDto createRepeatBlockPolicy(RepeatBlockPolicyReqDto request, AuthUserDetails auth) {

		// 반복적 차단 정보 생성
		repeatBlockMapper.insertRepeatBlock(request);
		
		// 반복적 차단 요일, 시간 생성
        if (request.getDays() != null && !request.getDays().isEmpty()) {
            repeatBlockMapper.insertRepeatBlockDays(request.getRepeatBlockId(), request.getDays());
        }
        
        List<RepeatBlockDayResDto> dayResList = request.getDays() != null
                ? request.getDays().stream()
                        .map(d -> RepeatBlockDayResDto.builder()
                                .dayOfWeek(d.getDayOfWeek())
                                .startAt(d.getStartAt())
                                .endAt(d.getEndAt())
                                .build())
                        .toList()
                : List.of();

        return RepeatBlockPolicyResDto.builder()
                .repeatBlockId(request.getRepeatBlockId())
                .isActive(request.getIsActive())
                .days(dayResList)
                .build();

	}

	@Override
	public RepeatBlockPolicyResDto updateRepeatBlockPolicy(Long repeatBlockId, RepeatBlockPolicyReqDto request,
			AuthUserDetails auth) {
		// 로직 결정 후 작성하기
		return null;
	}

	@Override
	public RepeatBlockPolicyResDto deleteRepeatBlockPolicy(Long repeatBlockId, AuthUserDetails auth) {
		
	    RepeatBlockPolicyResDto policy = repeatBlockMapper.selectRepeatBlockById(repeatBlockId);
	    if (policy == null) {
	    	// error code 로직 확인해서 변경 필요
	        throw new IllegalArgumentException("삭제할 반복적 차단 정책이 없습니다. repeatBlockId=" + repeatBlockId);
	    }

	    // soft delete
	    repeatBlockMapper.deleteRepeatBlock(repeatBlockId);

	    return policy;
	}

	@Override
	public ImmediateBlockResDto getImmediateBlockPolicy(Long lineId, AuthUserDetails auth) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ImmediateBlockResDto updateImmediateBlockPolicy(Long lineId, ImmediateBlockReqDto request,
			AuthUserDetails auth) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public LimitPolicyResDto getLimitPolicy(Long lineId, AuthUserDetails auth) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public LimitPolicyResDto toggleDailyTotalLimitPolicy(Long lineId, AuthUserDetails auth) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public LimitPolicyResDto updateDailyTotalLimitPolicyValue(LimitPolicyUpdateReqDto request, AuthUserDetails auth) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public LimitPolicyResDto toggleSharedPoolLimitPolicy(Long lineId, AuthUserDetails auth) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public LimitPolicyResDto updateSharedPoolLimitPolicyValue(LimitPolicyUpdateReqDto request, AuthUserDetails auth) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<AppPolicyResDto> getAppPolicies(Long lineId, AuthUserDetails auth) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AppPolicyResDto createAppPolicy(AppPolicyCreateReqDto request, AuthUserDetails auth) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AppPolicyResDto updateAppDataLimit(AppDataLimitUpdateReqDto request, AuthUserDetails auth) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AppPolicyResDto updateAppSpeedLimit(AppSpeedLimitUpdateReqDto request, AuthUserDetails auth) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void toggleAppPolicyActive(Long appPolicyId, AuthUserDetails auth) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void deleteAppPolicy(Long appPolicyId, AuthUserDetails auth) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public AppliedPolicyResDto getAppliedPolicies(Long lineId, AuthUserDetails auth) {
		// TODO Auto-generated method stub
		return null;
	}

    
}
