package com.pooli.policy.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.auth.service.AuthUserDetails;
import com.pooli.policy.domain.entity.AdminHistory;
import com.pooli.policy.domain.entity.PolicyHistory;
import com.pooli.policy.repository.AdminHistoryRepository;
import com.pooli.policy.repository.PolicyHistoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolicyHistoryServiceImpl implements PolicyHistoryService {

    private final PolicyHistoryRepository policyHistoryRepository;
    private final AdminHistoryRepository adminHistoryRepository;
    private final ObjectMapper objectMapper;

    private static final Set<String> ADMIN_TABLES = Set.of("ADMIN_POLICY", "ADMIN_POLICY_CATEGORY");

    @Override
    public void log(String tableName, String event, Object targetId, Object before, Object after) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String userId = null;
            Long lineId = null;

            if (auth != null && auth.getPrincipal() instanceof AuthUserDetails userDetails) {
                userId = String.valueOf(userDetails.getUserId());
                lineId = userDetails.getLineId();
            }

            String targetIdStr = targetId != null ? String.valueOf(targetId) : null;
            Map<String, Object> details = calculateDetails(event, before, after);

            if (isAdminTable(tableName)) {
                AdminHistory adminHistory = AdminHistory.builder()
                        .tableName(tableName)
                        .timestamp(LocalDateTime.now())
                        .targetId(targetIdStr)
                        .userId(userId)
                        .event(event)
                        .update(details)
                        .build();
                adminHistoryRepository.save(adminHistory);
            } else {
                PolicyHistory policyHistory = PolicyHistory.builder()
                        .tableName(tableName)
                        .timestamp(LocalDateTime.now())
                        .targetId(targetIdStr)
                        .userId(userId)
                        .lineId(lineId)
                        .event(event)
                        .update(details)
                        .build();
                policyHistoryRepository.save(policyHistory);
            }
        } catch (Exception e) {
            log.error("Failed to log policy history", e);
        }
    }

    private boolean isAdminTable(String tableName) {
        return tableName != null && ADMIN_TABLES.contains(tableName.toUpperCase());
    }

    private Map<String, Object> calculateDetails(String event, Object before, Object after) {
        if (!"UPDATE".equalsIgnoreCase(event) || (before == null && after == null)) {
            return null;
        }

        Object diff = recursiveDiff(before, after);
        if (diff instanceof Map map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private Object recursiveDiff(Object before, Object after) {
        if (Objects.equals(before, after)) return null;

        Map<String, Object> beforeMap = toMap(before);
        Map<String, Object> afterMap = toMap(after);

        if (beforeMap != null || afterMap != null) {
            Map<String, Object> result = new HashMap<>();
            Map<String, Object> bMap = beforeMap != null ? beforeMap : new HashMap<>();
            Map<String, Object> aMap = afterMap != null ? afterMap : new HashMap<>();

            Set<String> allKeys = new HashSet<>(bMap.keySet());
            allKeys.addAll(aMap.keySet());

            for (String key : allKeys) {
                Object diff = recursiveDiff(bMap.get(key), aMap.get(key));
                if (diff != null) {
                    result.put(key, diff);
                }
            }
            return result.isEmpty() ? null : result;
        } else if (before instanceof List || after instanceof List) {
            List<?> bList = before instanceof List ? (List<?>) before : new ArrayList<>();
            List<?> aList = after instanceof List ? (List<?>) after : new ArrayList<>();
            Map<String, Object> result = new HashMap<>();
            int maxSize = Math.max(bList.size(), aList.size());
            for (int i = 0; i < maxSize; i++) {
                Object bVal = i < bList.size() ? bList.get(i) : null;
                Object aVal = i < aList.size() ? aList.get(i) : null;
                Object diff = recursiveDiff(bVal, aVal);
                if (diff != null) {
                    result.put(String.valueOf(i), diff);
                }
            }
            return result.isEmpty() ? null : result;
        } else {
            Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("before", before);
            detail.put("after", after);
            return detail;
        }
    }

    private Map<String, Object> toMap(Object obj) {
        if (obj == null || obj instanceof String || obj instanceof Number || obj instanceof Boolean 
                || obj instanceof List || obj instanceof java.util.Date 
                || obj instanceof java.time.temporal.Temporal) {
            return null;
        }
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        try {
            return objectMapper.convertValue(obj, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
