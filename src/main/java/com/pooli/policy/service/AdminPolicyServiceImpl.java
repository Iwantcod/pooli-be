package com.pooli.policy.service;

import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pooli.notification.domain.dto.request.NotiSendReqDto;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.domain.enums.NotificationTargetType;
import com.pooli.notification.service.AlarmHistoryService;
import com.pooli.traffic.service.policy.TrafficPolicyWriteThroughService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final PolicyHistoryService policyHistoryService;
    private final ObjectProvider<TrafficPolicyWriteThroughService> trafficPolicyWriteThroughServiceProvider;

    @Override
    @Transactional(readOnly = true)
    public List<AdminPolicyResDto> getAllPolicies() {
        return adminPolicyMapper.selectAllPolicies();
    }

    @Override
    public AdminPolicyResDto createPolicy(AdminPolicyReqDto request) {
        adminPolicyMapper.insertPolicy(request);

        if (request.getPolicyId() != null) {
            applyWriteThrough(
                    "admin_policy_create policyId=" + request.getPolicyId(),
                    writeThroughService -> writeThroughService.syncPolicyActivation(request.getPolicyId(), false)
            );
        }

        // DB 최신 상태 재조회 (updatedAt 등 포함)
        AdminPolicyResDto response = adminPolicyMapper.selectPolicyById(request.getPolicyId());

        // MongoDB 이력 저장
        policyHistoryService.log("ADMIN_POLICY", "CREATE", request.getPolicyId(), null, response);

        return response;
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

        // DB 최신 상태 재조회 (updatedAt 등 포함)
        AdminPolicyResDto updated = adminPolicyMapper.selectPolicyById(policyId);
        
        // MongoDB 이력 저장
        policyHistoryService.log("ADMIN_POLICY", "UPDATE", policyId, existing, updated);

        return updated;
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

        // MongoDB 이력 저장
        policyHistoryService.log("ADMIN_POLICY", "DELETE", policyId, existing, null);

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
        sendPolicyNotification(
                Boolean.TRUE.equals(request.getIsActive()) ? AlarmType.ACTIVATE_POLICY : AlarmType.DEACTIVATE_POLICY,
                NotificationTargetType.OWNER,
                policyId,
                existing.getPolicyCategoryId(),
                existing.getPolicyName()
        );

        // DB 최신 상태 재조회 (updatedAt 등 포함)
        AdminPolicyResDto updated = adminPolicyMapper.selectPolicyById(policyId);

        AdminPolicyActiveResDto response = AdminPolicyActiveResDto.builder()
                .policyId(policyId)
                .isActive(request.getIsActive())
                .updatedAt(updated.getUpdatedAt())
                .build();
        
        // MongoDB 이력 저장
        policyHistoryService.log("ADMIN_POLICY", "UPDATE", policyId, existing, response);

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminPolicyCateResDto> getCategories() {
        return adminPolicyMapper.selectAllCategories();
    }

    @Override
    public AdminPolicyCateResDto createCategory(AdminCategoryReqDto request) {
    	
        adminPolicyMapper.insertCategory(request);

        // DB 최신 상태 재조회 (updatedAt 등 포함)
        AdminPolicyCateResDto response = adminPolicyMapper.selectCategoryById(request.getPolicyCategoryId());

        // MongoDB 이력 저장
        policyHistoryService.log("ADMIN_POLICY_CATEGORY", "CREATE", request.getPolicyCategoryId(), null, response);

        return response;
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

        // DB 최신 상태 재조회 (updatedAt 등 포함)
        AdminPolicyCateResDto response = adminPolicyMapper.selectCategoryById(policyCategoryId);
        
        // MongoDB 이력 저장
        policyHistoryService.log("ADMIN_POLICY_CATEGORY", "UPDATE", policyCategoryId, category, response);

        return response;
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

        // MongoDB 이력 저장
        policyHistoryService.log("ADMIN_POLICY_CATEGORY", "DELETE", policyCategoryId, category, null);

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

    private void sendPolicyNotification(AlarmType type, NotificationTargetType targetType, Integer policyId, Integer policyCategoryId, String name) {
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
        req.setTargetType(targetType);
        req.setValue(value);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    alarmHistoryService.sendNotificationAsync(req);
                }
            });
            return;
        }

        alarmHistoryService.sendNotificationAsync(req);
    }
}
