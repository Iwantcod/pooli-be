package com.pooli.policy.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.notification.domain.dto.request.NotiSendReqDto;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.domain.enums.NotificationTargetType;
import com.pooli.notification.service.AlarmHistoryService;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockRehydrateAllResDto;
import com.pooli.traffic.service.policy.TrafficPolicyWriteThroughService;
import com.pooli.traffic.service.outbox.PolicySyncResult;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import com.pooli.policy.mapper.RepeatBlockMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminPolicyServiceImpl implements AdminPolicyService {

    private final AdminPolicyMapper adminPolicyMapper;
    private final AlarmHistoryService alarmHistoryService;
    private final PolicyHistoryService policyHistoryService;
    private final ObjectProvider<TrafficPolicyWriteThroughService> trafficPolicyWriteThroughServiceProvider;
    private final RepeatBlockMapper repeatBlockMapper;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;

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

    @Override
    @Transactional(readOnly = true)
    public RepeatBlockRehydrateAllResDto rehydrateAllRepeatBlocksToRedis() {
        TrafficPolicyWriteThroughService writeThroughService = requireWriteThroughServiceForRehydrate();

        List<Long> lineIds = loadLineIdsFromExistingRepeatBlockKeys();
        if (lineIds == null || lineIds.isEmpty()) {
            return RepeatBlockRehydrateAllResDto.builder()
                    .totalLineCount(0)
                    .successCount(0)
                    .failureCount(0)
                    .failedLineIds(List.of())
                    .build();
        }

        int successCount = 0;
        List<Long> failedLineIds = new ArrayList<>();

        // 운영자가 즉시 상태를 확인할 수 있도록 동기 방식으로 모든 line을 순회 반영한다.
        for (Long lineId : lineIds) {
            if (lineId == null || lineId <= 0) {
                failedLineIds.add(lineId);
                continue;
            }

            List<RepeatBlockPolicyResDto> repeatBlocks = repeatBlockMapper.selectRepeatBlocksByLineId(lineId);
            long version = System.currentTimeMillis();
            PolicySyncResult syncResult = writeThroughService.syncRepeatBlockUntracked(lineId, repeatBlocks, version);
            if (isSuccessEquivalent(syncResult)) {
                successCount++;
                continue;
            }

            failedLineIds.add(lineId);
            log.warn("repeat_block_rehydrate_failed lineId={} result={}", lineId, syncResult);
        }

        int totalLineCount = lineIds.size();
        int failureCount = failedLineIds.size();
        log.info(
                "repeat_block_rehydrate_all_completed totalLineCount={} successCount={} failureCount={}",
                totalLineCount,
                successCount,
                failureCount
        );

        return RepeatBlockRehydrateAllResDto.builder()
                .totalLineCount(totalLineCount)
                .successCount(successCount)
                .failureCount(failureCount)
                .failedLineIds(failedLineIds)
                .build();
    }

    /**
     * Redis에 이미 존재하는 repeat_block 키에서 lineId 목록을 추출합니다.
     * 관리자 일괄 재적재 시 불필요한 DB full-scan을 피하기 위한 대상 축소 단계입니다.
     */
    private List<Long> loadLineIdsFromExistingRepeatBlockKeys() {
        String repeatBlockPattern = trafficRedisKeyFactory.repeatBlockKeyPattern();
        String repeatBlockPrefix = trafficRedisKeyFactory.repeatBlockKeyPrefix();

        Set<String> existingRepeatBlockKeys = cacheStringRedisTemplate.keys(repeatBlockPattern);
        if (existingRepeatBlockKeys == null || existingRepeatBlockKeys.isEmpty()) {
            return List.of();
        }

        Set<Long> lineIds = new LinkedHashSet<>();
        for (String key : existingRepeatBlockKeys) {
            Long parsedLineId = parseLineIdFromRepeatBlockKey(key, repeatBlockPrefix);
            if (parsedLineId == null) {
                log.warn("repeat_block_rehydrate_skip_invalid_key key={} prefix={}", key, repeatBlockPrefix);
                continue;
            }
            lineIds.add(parsedLineId);
        }
        return new ArrayList<>(lineIds);
    }

    /**
     * namespaced repeat_block 키에서 lineId suffix를 파싱합니다.
     */
    private Long parseLineIdFromRepeatBlockKey(String key, String keyPrefix) {
        if (key == null || keyPrefix == null || !key.startsWith(keyPrefix)) {
            return null;
        }
        String suffix = key.substring(keyPrefix.length()).trim();
        if (suffix.isEmpty()) {
            return null;
        }
        try {
            long lineId = Long.parseLong(suffix);
            if (lineId <= 0) {
                return null;
            }
            return lineId;
        } catch (NumberFormatException e) {
            return null;
        }
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

    private TrafficPolicyWriteThroughService requireWriteThroughServiceForRehydrate() {
        if (trafficPolicyWriteThroughServiceProvider == null) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Repeat block Redis rehydrate를 수행할 수 없습니다. write-through provider가 비어 있습니다."
            );
        }

        TrafficPolicyWriteThroughService writeThroughService = trafficPolicyWriteThroughServiceProvider.getIfAvailable();
        if (writeThroughService == null) {
            throw new ApplicationException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Repeat block Redis rehydrate를 수행할 수 없습니다. cache Redis profile이 비활성입니다."
            );
        }
        return writeThroughService;
    }

    private boolean isSuccessEquivalent(PolicySyncResult result) {
        return result == PolicySyncResult.SUCCESS || result == PolicySyncResult.STALE_REJECTED;
    }
}
