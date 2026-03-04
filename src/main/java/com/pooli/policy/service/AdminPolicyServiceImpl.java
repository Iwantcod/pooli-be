package com.pooli.policy.service;

import java.util.List;

import com.pooli.policy.domain.dto.request.AdminPolicyActiveReqDto;
import com.pooli.policy.domain.dto.response.AdminPolicyResDto;
import com.pooli.policy.domain.dto.response.LineAppUsageResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyActiveResDto;
import com.pooli.policy.domain.dto.response.PolicyDeactivationResDto;

public class AdminPolicyServiceImpl implements AdminPolicyService{

	@Override
	public List<AdminPolicyResDto> getAllPolicies() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AdminPolicyActiveResDto activatePolicy(AdminPolicyActiveReqDto request) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public PolicyDeactivationResDto deactivatePolicy(Long policyId) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<LineAppUsageResDto> getLineAppUsageStatistics(Long lineId) {
		// TODO Auto-generated method stub
		return null;
	}

}
