package com.pooli.policy.service;

import com.pooli.policy.domain.dto.request.AdminCategoryReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyActiveReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyReqDto;
import com.pooli.policy.domain.dto.response.AdminPolicyActiveResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyCateResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockRehydrateAllResDto;

import java.util.List;

public interface AdminPolicyService {

    // 전체 정책 목록 조회 (활성/비활성 포함)
    List<AdminPolicyResDto> getAllPolicies();

    // 정책 추가
    AdminPolicyResDto createPolicy(AdminPolicyReqDto request);

    // 정책 수정
    AdminPolicyResDto updatePolicy(Integer policyId, AdminPolicyReqDto request);

    // 정책 삭제
    AdminPolicyResDto deletePolicy(Integer policyId);

    // 정책 활성화/비활성화 상태 변경
    AdminPolicyActiveResDto updateActivationPolicy(Integer policyId, AdminPolicyActiveReqDto request);

    // 정책 카테고리 조회
    List<AdminPolicyCateResDto> getCategories();

    // 정책 카테고리 추가
    AdminPolicyCateResDto createCategory(AdminCategoryReqDto request);

    // 정책 카테고리 수정
    AdminPolicyCateResDto updateCategory(Integer policyCategoryId, AdminCategoryReqDto request);

    // 정책 카테고리 삭제
    AdminPolicyCateResDto deleteCategory(Integer policyCategoryId);

    // repeat block Redis 전체 재적재
    RepeatBlockRehydrateAllResDto rehydrateAllRepeatBlocksToRedis();

}
