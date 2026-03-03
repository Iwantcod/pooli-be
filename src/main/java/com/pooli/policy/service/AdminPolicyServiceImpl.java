package com.pooli.policy.service;

import java.util.List;

import com.pooli.policy.domain.dto.request.AdminCategoryReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyActiveReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyReqDto;
import com.pooli.policy.domain.dto.response.AdminPolicyResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyActiveResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyCateResDto;

public class AdminPolicyServiceImpl implements AdminPolicyService{

	@Override
	public List<AdminPolicyResDto> getAllPolicies() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AdminPolicyResDto createPolicy(AdminPolicyReqDto request) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AdminPolicyResDto updatePolicy(Integer policyId, AdminPolicyReqDto request) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AdminPolicyActiveResDto deletePolicy(Integer policyId) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AdminPolicyActiveResDto updateActivationPolicy(Integer policyId, AdminPolicyActiveReqDto request) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AdminPolicyCateResDto getCategories() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AdminPolicyCateResDto createCategory(AdminCategoryReqDto request) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AdminPolicyCateResDto updateCategory(Integer policyCategoryId, AdminCategoryReqDto request) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AdminPolicyCateResDto deleteCategory(Integer policyCategoryId) {
		// TODO Auto-generated method stub
		return null;
	}



}
