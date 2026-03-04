package com.pooli.policy.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import com.pooli.policy.domain.dto.response.ActivePolicyResDto;

@Mapper
public interface PolicyBackOfficeMapper {
	
	// 백오피스에서 활성화한 전체 정책 목록 조회
    List<ActivePolicyResDto> selectActivePolicies();
}
