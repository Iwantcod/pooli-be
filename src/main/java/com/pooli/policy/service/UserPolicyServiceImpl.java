package com.pooli.policy.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.policy.domain.dto.request.AppDataLimitUpdateReqDto;
import com.pooli.policy.domain.dto.request.AppPolicyCreateReqDto;
import com.pooli.policy.domain.dto.request.AppSpeedLimitUpdateReqDto;
import com.pooli.policy.domain.dto.request.ImmediateBlockReqDto;
import com.pooli.policy.domain.dto.request.LimitPolicyUpdateReqDto;
import com.pooli.policy.domain.dto.request.RepeatBlockDayReqDto;
import com.pooli.policy.domain.dto.request.RepeatBlockPolicyReqDto;
import com.pooli.policy.domain.dto.response.ActivePolicyResDto;
import com.pooli.policy.domain.dto.response.AppPolicyResDto;
import com.pooli.policy.domain.dto.response.AppliedPolicyResDto;
import com.pooli.policy.domain.dto.response.ImmediateBlockResDto;
import com.pooli.policy.domain.dto.response.LimitPolicyResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockDayResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import com.pooli.policy.exception.PolicyErrorCode;
import com.pooli.policy.mapper.ImmediateBlockMapper;
import com.pooli.policy.mapper.PolicyBackOfficeMapper;
import com.pooli.policy.mapper.RepeatBlockDayMapper;
import com.pooli.policy.mapper.RepeatBlockMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserPolicyServiceImpl implements UserPolicyService {

    private final PolicyBackOfficeMapper policyBackOfficeMapper;
    private final RepeatBlockMapper repeatBlockMapper;
    private final RepeatBlockDayMapper repeatBlockDayMapper;
    private final ImmediateBlockMapper immediateBlockMapper;
    
	@Override
	public List<ActivePolicyResDto> getActivePolicies() {
		
		return policyBackOfficeMapper.selectActivePolicies();
	}

	@Override
	public List<RepeatBlockPolicyResDto> getRepeatBlockPolicies(Long lineId, AuthUserDetails auth) {

	    return repeatBlockMapper.selectRepeatBlocksByLineId(lineId);
	}

	@Override
	@Transactional
	public RepeatBlockPolicyResDto createRepeatBlockPolicy(RepeatBlockPolicyReqDto request, AuthUserDetails auth) {

        Long lineId = auth.getLineId();
        

	    List<RepeatBlockDayReqDto> days = request.getDays();

	    // 반복적 차단 요일/시간 중복 체크
	    if (days != null && !days.isEmpty()) {
	        for (RepeatBlockDayReqDto day : days) {
	            boolean exists = repeatBlockMapper.isDuplicatedRepeatBlocks(
	            		lineId,
	                    day.getDayOfWeek(),
	                    day.getStartAt(),
	                    day.getEndAt()
	            );
	            if (exists) {
	            	throw new ApplicationException(PolicyErrorCode.BLOCK_POLICY_CONFLICT); 
	            }
	        }
	        
	    }
	    
	    // 새로운 차단이면 DB 삽입
	    repeatBlockMapper.insertRepeatBlock(request);

	    if (days != null && !days.isEmpty()) {
	        repeatBlockDayMapper.insertRepeatBlockDays(request.getRepeatBlockId(), days);
	    }

	    // DTO 반환
	    List<RepeatBlockDayResDto> dayResList = days != null
	            ? days.stream()
	                  .map(d -> RepeatBlockDayResDto.builder()
	                          .dayOfWeek(d.getDayOfWeek())
	                          .startAt(d.getStartAt())
	                          .endAt(d.getEndAt())
	                          .build())
	                  .toList()
	            : List.of();

	    return RepeatBlockPolicyResDto.builder()
	            .repeatBlockId(request.getRepeatBlockId())
	            .lineId(lineId)
	            .isActive(request.getIsActive())
	            .days(dayResList)
	            .build();
	}

	@Override
	public RepeatBlockPolicyResDto updateRepeatBlockPolicy(Long repeatBlockId, RepeatBlockPolicyReqDto request,
			AuthUserDetails auth) {
		// 반복 차단 요일 및 시간대 정보를 삭제한 후 새로 삽입하기
		deleteRepeatBlockPolicy(repeatBlockId, auth);
		return createRepeatBlockPolicy(request, auth);
	}

	@Override
	public RepeatBlockPolicyResDto deleteRepeatBlockPolicy(Long repeatBlockId, AuthUserDetails auth) {

	    // 반복적 차단 정보 없음
		RepeatBlockPolicyResDto exist = repeatBlockMapper.selectRepeatBlockById(repeatBlockId);
	    
		if (exist == null) {
	        throw new ApplicationException(PolicyErrorCode.REPEAT_BLOCK_NOT_FOUND);
	    } 
		
	    // soft delete
	    repeatBlockMapper.deleteRepeatBlock(repeatBlockId);
	    repeatBlockDayMapper.deleteRepeatDayBlock(repeatBlockId);	    

	    return RepeatBlockPolicyResDto.builder()
	    		.repeatBlockId(repeatBlockId)
	    		.lineId(auth.getLineId())
	    		.isActive(false)
	    		.build();
	}

	@Override
	public ImmediateBlockResDto getImmediateBlockPolicy(Long lineId, AuthUserDetails auth) {

	    ImmediateBlockResDto immBlock = immediateBlockMapper.selectImmediateBlockPolicy(lineId);
	       
	    // 즉시 차단 정보가 없을 때
	    if(immBlock == null) {
	    	return ImmediateBlockResDto.builder()
		    		.lineId(lineId)
		    		.blockEndAt(null)
		    		.build();
	    }
	    
	    return ImmediateBlockResDto.builder()
	    		.lineId(lineId)
	    		.blockEndAt(immBlock.getBlockEndAt())
	    		.build();
	
	}

	@Override
	public ImmediateBlockResDto updateImmediateBlockPolicy(Long lineId, ImmediateBlockReqDto request,
			AuthUserDetails auth) {

	    immediateBlockMapper.updateImmediateBlockPolicy(lineId, request);
	    			    
        return ImmediateBlockResDto.builder()
        		.lineId(lineId)
        		.blockEndAt(request.getBlockEndAt())
        		.build();
        
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
			
		// 제한 정책, 앱 정보 받아와서 추가하기(return에도)
	    List<RepeatBlockPolicyResDto> repeatBlockPolicyList = repeatBlockMapper.selectRepeatBlocksByLineId(lineId);

	    ImmediateBlockResDto immediateBlock = immediateBlockMapper.selectImmediateBlockPolicy(lineId);
	    
	    // null 안전하게 처리
	    ImmediateBlockResDto immBlock = (immediateBlock != null)
	        ? ImmediateBlockResDto.builder()
	            .lineId(lineId)                  
	            .blockEndAt(immediateBlock.getBlockEndAt())
	            .build()
	        : null;                             

	    
	    return AppliedPolicyResDto.builder()
	            .repeatBlockPolicyList(repeatBlockPolicyList)
	            .immediateBlock(immBlock)
	            .build();

	}

    
}
