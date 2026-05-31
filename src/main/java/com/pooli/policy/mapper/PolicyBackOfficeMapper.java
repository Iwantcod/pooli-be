package com.pooli.policy.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.policy.domain.dto.response.ActivePolicyResDto;
import com.pooli.policy.domain.dto.response.PolicyActivationSnapshotResDto;

@Mapper
public interface PolicyBackOfficeMapper {
	
	// 백오피스에서 활성화한 전체 정책 목록 조회
    List<ActivePolicyResDto> selectActivePolicies();
    
	// 백오피스에서 활성화한 정책 단건 조회
    ActivePolicyResDto selectActivePolicy(@Param("policyId") Integer policyId);

    // 정책 전역 활성화 bootstrap/reconciliation용 스냅샷 조회
    // ※ policy_id = 8 (장애 복구 전역 플래그)은 복구 프로세스가 Redis에서 동적으로 관리하며, DB 동기화 시 덮어쓰기 방지를 위해 제외합니다.
    List<PolicyActivationSnapshotResDto> selectPolicyActivationSnapshot();
	    
}
