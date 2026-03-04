package com.pooli.policy.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.policy.domain.dto.request.AdminCategoryReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyActiveReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyReqDto;
import com.pooli.policy.domain.dto.response.AdminPolicyActiveResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyCateResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyResDto;
import com.pooli.policy.mapper.AdminPolicyMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminPolicyServiceImpl implements AdminPolicyService {

    private final AdminPolicyMapper adminPolicyMapper;

    @Override
    @Transactional(readOnly = true)
    public List<AdminPolicyResDto> getAllPolicies() {
        return adminPolicyMapper.selectAllPolicies();
    }

    @Override
    public AdminPolicyResDto createPolicy(AdminPolicyReqDto request) {
        adminPolicyMapper.insertPolicy(request);
        return AdminPolicyResDto.builder()
                .policyId(request.getPolicyId())
                .policyName(request.getPolicyName())
                .policyCategoryId(request.getPolicyCategoryId())
                .isActive(false)
                .build();
    }

    @Override
    public AdminPolicyResDto updatePolicy(Integer policyId, AdminPolicyReqDto request) {
        adminPolicyMapper.updatePolicy(policyId, request);
        return AdminPolicyResDto.builder()
                .policyId(policyId)
                .policyName(request.getPolicyName())
                .policyCategoryId(request.getPolicyCategoryId())
                .isActive(request.getIsActive())
                .build();
    }

    @Override
    public AdminPolicyResDto deletePolicy(Integer policyId) {
        adminPolicyMapper.deletePolicy(policyId);
        return AdminPolicyResDto.builder()
                .policyId(policyId)
                .build();
    }

    @Override
    public AdminPolicyActiveResDto updateActivationPolicy(Integer policyId, AdminPolicyActiveReqDto request) {
        adminPolicyMapper.updatePolicyActiveStatus(policyId, request);
        return AdminPolicyActiveResDto.builder()
                .policyId(policyId)
                .isActive(request.getIsActive())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminPolicyCateResDto> getCategories() {
        return adminPolicyMapper.selectAllCategories();
    }

    @Override
    public AdminPolicyCateResDto createCategory(AdminCategoryReqDto request) {
        adminPolicyMapper.insertCategory(request);
        return AdminPolicyCateResDto.builder()
                .policyCategoryId(request.getPolicyCategoryId())
                .policyCategoryName(request.getPolicyCategoryName())
                .build();
    }

    @Override
    public AdminPolicyCateResDto updateCategory(Integer policyCategoryId, AdminCategoryReqDto request) {
        adminPolicyMapper.updateCategory(policyCategoryId, request);
        return AdminPolicyCateResDto.builder()
                .policyCategoryId(policyCategoryId)
                .policyCategoryName(request.getPolicyCategoryName())
                .build();
    }

    @Override
    public AdminPolicyCateResDto deleteCategory(Integer policyCategoryId) {
        adminPolicyMapper.deleteCategory(policyCategoryId);
        return AdminPolicyCateResDto.builder()
                .policyCategoryId(policyCategoryId)
                .build();
    }
}
