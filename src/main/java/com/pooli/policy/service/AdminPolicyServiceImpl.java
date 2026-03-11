package com.pooli.policy.service;

import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pooli.notification.domain.dto.request.NotiSendReqDto;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.domain.enums.NotificationTargetType;
import com.pooli.notification.service.AlarmHistoryService;
import com.pooli.traffic.service.TrafficPolicyWriteThroughService;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<TrafficPolicyWriteThroughService> trafficPolicyWriteThroughServiceProvider;

    @Override
    @Transactional(readOnly = true)
    public List<AdminPolicyResDto> getAllPolicies() {
        return adminPolicyMapper.selectAllPolicies();
    }

    @Override
    public AdminPolicyResDto createPolicy(AdminPolicyReqDto request) {
        adminPolicyMapper.insertPolicy(request);

        // 신규 정책은 기본 비활성 상태이므로 policy 키를 비활성 상태로 동기화한다.
        if (request.getPolicyId() != null) {
            applyWriteThrough(
                    "admin_policy_create policyId=" + request.getPolicyId(),
                    writeThroughService -> writeThroughService.syncPolicyActivation(request.getPolicyId(), false)
            );
        }

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

        // 정책 수정 시 SQL에서 is_active=false로 고정되므로 Redis도 비활성으로 맞춘다.
        applyWriteThrough(
                "admin_policy_update policyId=" + policyId,
                writeThroughService -> writeThroughService.syncPolicyActivation(policyId, false)
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

        applyWriteThrough(
                "admin_policy_delete policyId=" + policyId,
                writeThroughService -> writeThroughService.syncPolicyActivation(policyId, false)
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
        applyWriteThrough(
                "admin_policy_toggle_activation policyId=" + policyId,
                writeThroughService -> writeThroughService.syncPolicyActivation(policyId, Boolean.TRUE.equals(request.getIsActive()))
        );
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

        return AdminPolicyCateResDto.builder()
                .policyCategoryId(policyCategoryId)
                .build();
    }

    private void applyWriteThrough(
            String operationName,
            java.util.function.Consumer<TrafficPolicyWriteThroughService> callback
    ) {
        // 단위 테스트(@InjectMocks)에서는 ObjectProvider가 주입되지 않을 수 있다.
        if (trafficPolicyWriteThroughServiceProvider == null) {
            return;
        }

        TrafficPolicyWriteThroughService writeThroughService = trafficPolicyWriteThroughServiceProvider.getIfAvailable();
        if (writeThroughService == null) {
            // cache Redis가 없는 프로파일에서는 정책 키 동기화를 생략한다.
            return;
        }
        callback.accept(writeThroughService);
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
