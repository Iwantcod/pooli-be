package com.pooli.policy.service;

import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pooli.notification.domain.dto.request.NotiSendReqDto;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.domain.enums.NotificationTargetType;
import com.pooli.notification.service.AlarmHistoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.common.exception.ApplicationException;
import com.pooli.policy.domain.dto.request.AdminCategoryReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyActiveReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyReqDto;
import com.pooli.policy.domain.dto.response.AdminPolicyActiveResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyCateResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyResDto;
import com.pooli.policy.exception.PolicyErrorCode;
import com.pooli.policy.mapper.AdminPolicyMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminPolicyServiceImpl implements AdminPolicyService {

    private final AdminPolicyMapper adminPolicyMapper;
    private final AlarmHistoryService alarmHistoryService;

    @Override
    @Transactional(readOnly = true)
    public List<AdminPolicyResDto> getAllPolicies() {
        return adminPolicyMapper.selectAllPolicies();
    }

    @Override
    public AdminPolicyResDto createPolicy(AdminPolicyReqDto request) {
        adminPolicyMapper.insertPolicy(request);
        sendOwnerNotification(
                AlarmType.ACTIVATE_POLICY,
                request.getPolicyId(),
                null,
                request.getPolicyName()
        );

        return AdminPolicyResDto.builder()
                .policyId(request.getPolicyId())
                .policyName(request.getPolicyName())
                .policyCategoryId(request.getPolicyCategoryId())
                .isActive(false)
                .build();
    }

    @Override
    public AdminPolicyResDto updatePolicy(Integer policyId, AdminPolicyReqDto request) {

        AdminPolicyResDto existing = adminPolicyMapper.selectPolicyById(policyId);
        if (existing == null) {
            throw new ApplicationException(PolicyErrorCode.ADMIN_POLICY_NOT_FOUND);
        }

        adminPolicyMapper.updatePolicy(policyId, request);
        sendOwnerNotification(
                AlarmType.ACTIVATE_POLICY,
                policyId,
                request.getPolicyCategoryId(),
                request.getPolicyName()
        );

        return AdminPolicyResDto.builder()
                .policyId(policyId)
                .policyName(request.getPolicyName())
                .policyCategoryId(request.getPolicyCategoryId())
                .isActive(request.getIsActive())
                .build();
    }

    @Override
    public AdminPolicyResDto deletePolicy(Integer policyId) {

        AdminPolicyResDto existing = adminPolicyMapper.selectPolicyById(policyId);
        if (existing == null) {
            throw new ApplicationException(PolicyErrorCode.ADMIN_POLICY_NOT_FOUND);
        }

        adminPolicyMapper.deletePolicy(policyId);
        sendOwnerNotification(
                AlarmType.DEACTIVATE_POLICY,
                policyId,
                null,
                existing.getPolicyName()
        );

        return AdminPolicyResDto.builder()
                .policyId(policyId)
                .build();
    }

    @Override
    public AdminPolicyActiveResDto updateActivationPolicy(Integer policyId, AdminPolicyActiveReqDto request) {

        AdminPolicyResDto existing = adminPolicyMapper.selectPolicyById(policyId);
        if (existing == null) {
            throw new ApplicationException(PolicyErrorCode.ADMIN_POLICY_NOT_FOUND);
        }

        adminPolicyMapper.updatePolicyActiveStatus(policyId, request);
        sendOwnerNotification(
                Boolean.TRUE.equals(request.getIsActive()) ? AlarmType.ACTIVATE_POLICY : AlarmType.DEACTIVATE_POLICY,
                policyId,
                existing.getPolicyCategoryId(),
                existing.getPolicyName()
        );

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
        sendOwnerNotification(
                AlarmType.ACTIVATE_POLICY,
                null,
                request.getPolicyCategoryId(),
                request.getPolicyCategoryName()
        );

        return AdminPolicyCateResDto.builder()
                .policyCategoryId(request.getPolicyCategoryId())
                .policyCategoryName(request.getPolicyCategoryName())
                .build();
    }

    @Override
    public AdminPolicyCateResDto updateCategory(Integer policyCategoryId, AdminCategoryReqDto request) {

	    AdminPolicyCateResDto category =
	            adminPolicyMapper.selectCategoryById(policyCategoryId);

	    if (category == null) {
	        throw new ApplicationException(
	                PolicyErrorCode.ADMIN_POLICY_NOT_FOUND 
	        );
	    }

    	    
        adminPolicyMapper.updateCategory(policyCategoryId, request);
        sendOwnerNotification(
                AlarmType.ACTIVATE_POLICY,
                null,
                policyCategoryId,
                request.getPolicyCategoryName()
        );

        return AdminPolicyCateResDto.builder()
                .policyCategoryId(policyCategoryId)
                .policyCategoryName(request.getPolicyCategoryName())
                .build();
    }

    @Override
    public AdminPolicyCateResDto deleteCategory(Integer policyCategoryId) {

	    AdminPolicyCateResDto category =
	            adminPolicyMapper.selectCategoryById(policyCategoryId);

	    if (category == null) {
	        throw new ApplicationException(
	                PolicyErrorCode.ADMIN_POLICY_NOT_FOUND 
	        );
	    }
	    
        adminPolicyMapper.deleteCategory(policyCategoryId);
        sendOwnerNotification(
                AlarmType.DEACTIVATE_POLICY,
                null,
                policyCategoryId,
                category.getPolicyCategoryName()
        );

        return AdminPolicyCateResDto.builder()
                .policyCategoryId(policyCategoryId)
                .build();
    }

    private void sendOwnerNotification(AlarmType type, Integer policyId, Integer policyCategoryId, String name) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("type", type.name());
        if (policyId != null) {
            value.put("policyId", policyId);
        }
        if (policyCategoryId != null) {
            value.put("policyCategoryId", policyCategoryId);
        }
        if (name != null) {
            value.put("name", name);
        }

        NotiSendReqDto req = new NotiSendReqDto();
        req.setTargetType(NotificationTargetType.OWNER);
        req.setValue(value);

        alarmHistoryService.sendNotification(req);
    }
}
