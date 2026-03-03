package com.pooli.policy.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import com.pooli.policy.domain.dto.request.AdminPolicyActiveReqDto;
import com.pooli.policy.domain.dto.response.AdminPolicyResDto;
import com.pooli.policy.domain.dto.response.LineAppUsageResDto;

@Mapper
public interface AdminPolicyMapper {

	// 전체 정책 목록 조회(활성/비활성 모두 포함)
	List<AdminPolicyResDto> selectAllPolicies();
	  
	// 정책 추가 및 활성화
	int insertPolicy(AdminPolicyActiveReqDto request);

	// 정책 삭제 및 비활성화
	int deletePolicy(Long policyId);

	// 특정 구성원 앱별 사용량 통계 조회
	List<LineAppUsageResDto> selectLineAppUsageStatistics(Long lineId);

}
