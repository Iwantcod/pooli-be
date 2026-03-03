package com.pooli.policy.service;

import com.pooli.policy.domain.dto.request.AdminPolicyActiveReqDto;
import com.pooli.policy.domain.dto.response.AdminPolicyResDto;
import com.pooli.policy.domain.dto.response.LineAppUsageResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyActiveResDto;
import com.pooli.policy.domain.dto.response.PolicyDeactivationResDto;

import java.util.List;

public interface AdminPolicyService {

    /**
     * 전체 정책 목록 조회 (활성/비활성 포함)
     * Controller: ResponseEntity<List<AdminPolicyResDto>>
     * Params: 없음
     */
    List<AdminPolicyResDto> getAllPolicies();

    /**
     * 정책 추가 및 활성화
     * Controller: ResponseEntity<PolicyActivationResDto>
     * Params: Body(PolicyActivationReqDto)
     */
    AdminPolicyActiveResDto activatePolicy(AdminPolicyActiveReqDto request);

    /**
     * 정책 삭제 및 비활성화
     * Controller: ResponseEntity<PolicyDeactivationResDto>
     * Params: URL(policyId)
     */
    PolicyDeactivationResDto deactivatePolicy(Integer policyId);

    /**
     * 특정 구성원 앱별 사용량 통계 조회
     * Controller: ResponseEntity<List<LineAppUsageResDto>>
     * Params: URL(lineId)
     */
    List<LineAppUsageResDto> getLineAppUsageStatistics(Long lineId);
}
