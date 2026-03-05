package com.pooli.policy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pooli.common.exception.ApplicationException;
import com.pooli.notification.domain.dto.request.NotiSendReqDto;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.domain.enums.NotificationTargetType;
import com.pooli.notification.service.AlarmHistoryService;
import com.pooli.policy.domain.dto.request.AdminCategoryReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyActiveReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyReqDto;
import com.pooli.policy.domain.dto.response.AdminPolicyActiveResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyCateResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyResDto;
import com.pooli.policy.exception.PolicyErrorCode;
import com.pooli.policy.mapper.AdminPolicyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminPolicyServiceImplTest {

    @Mock
    private AdminPolicyMapper adminPolicyMapper;
    @Mock
    private AlarmHistoryService alarmHistoryService;

    @InjectMocks
    private AdminPolicyServiceImpl adminPolicyService;

    @Nested
    @DisplayName("조회 메서드")
    class ReadMethods {

        @Test
        void getAllPolicies_success() {
            when(adminPolicyMapper.selectAllPolicies()).thenReturn(List.of(
                    AdminPolicyResDto.builder().policyId(1).policyName("P1").build()
            ));

            List<AdminPolicyResDto> result = adminPolicyService.getAllPolicies();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPolicyName()).isEqualTo("P1");
        }

        @Test
        void getCategories_success() {
            when(adminPolicyMapper.selectAllCategories()).thenReturn(List.of(
                    AdminPolicyCateResDto.builder().policyCategoryId(1).policyCategoryName("차단").build()
            ));

            List<AdminPolicyCateResDto> result = adminPolicyService.getCategories();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPolicyCategoryName()).isEqualTo("차단");
        }
    }

    @Nested
    @DisplayName("쓰기 메서드")
    class WriteMethods {

        @Test
        void createPolicy_success() {
            AdminPolicyReqDto req = new AdminPolicyReqDto();
            req.setPolicyName("신규 정책");
            req.setPolicyCategoryId(1);
            doAnswer(inv -> {
                AdminPolicyReqDto arg = inv.getArgument(0);
                arg.setPolicyId(10);
                return 1;
            }).when(adminPolicyMapper).insertPolicy(any());

            AdminPolicyResDto result = adminPolicyService.createPolicy(req);

            assertThat(result.getPolicyId()).isEqualTo(10);
            verify(adminPolicyMapper).insertPolicy(req);
            verifyNotification(AlarmType.ACTIVATE_POLICY, 10, null, "신규 정책");
        }

        @Test
        void updatePolicy_success() {
            Integer policyId = 1;
            AdminPolicyReqDto req = new AdminPolicyReqDto();
            req.setPolicyName("수정 정책");
            req.setPolicyCategoryId(5);
            req.setIsActive(true);
            when(adminPolicyMapper.selectPolicyById(policyId))
                    .thenReturn(AdminPolicyResDto.builder().policyId(policyId).policyName("기존").build());
            when(adminPolicyMapper.updatePolicy(eq(policyId), any())).thenReturn(1);

            AdminPolicyResDto result = adminPolicyService.updatePolicy(policyId, req);

            assertThat(result.getPolicyName()).isEqualTo("수정 정책");
            verify(adminPolicyMapper).updatePolicy(policyId, req);
            verifyNotification(AlarmType.ACTIVATE_POLICY, policyId, 5, "수정 정책");
        }

        @Test
        void updatePolicy_notFound_throws() {
            when(adminPolicyMapper.selectPolicyById(1)).thenReturn(null);

            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> adminPolicyService.updatePolicy(1, new AdminPolicyReqDto()));

            assertThat(ex.getErrorCode()).isEqualTo(PolicyErrorCode.ADMIN_POLICY_NOT_FOUND);
        }

        @Test
        void deletePolicy_success() {
            Integer policyId = 2;
            when(adminPolicyMapper.selectPolicyById(policyId))
                    .thenReturn(AdminPolicyResDto.builder().policyId(policyId).policyName("삭제정책").build());
            when(adminPolicyMapper.deletePolicy(policyId)).thenReturn(1);

            AdminPolicyResDto result = adminPolicyService.deletePolicy(policyId);

            assertThat(result.getPolicyId()).isEqualTo(policyId);
            verify(adminPolicyMapper).deletePolicy(policyId);
            verifyNotification(AlarmType.DEACTIVATE_POLICY, policyId, null, "삭제정책");
        }

        @Test
        void updateActivationPolicy_success() {
            Integer policyId = 3;
            AdminPolicyActiveReqDto req = new AdminPolicyActiveReqDto();
            req.setIsActive(true);
            when(adminPolicyMapper.selectPolicyById(policyId)).thenReturn(
                    AdminPolicyResDto.builder().policyId(policyId).policyName("A").policyCategoryId(7).build());
            when(adminPolicyMapper.updatePolicyActiveStatus(eq(policyId), any())).thenReturn(1);

            AdminPolicyActiveResDto result = adminPolicyService.updateActivationPolicy(policyId, req);

            assertThat(result.getIsActive()).isTrue();
            verifyNotification(AlarmType.ACTIVATE_POLICY, policyId, 7, "A");
        }

        @Test
        void createCategory_success() {
            AdminCategoryReqDto req = new AdminCategoryReqDto();
            req.setPolicyCategoryName("광고");
            doAnswer(inv -> {
                AdminCategoryReqDto arg = inv.getArgument(0);
                arg.setPolicyCategoryId(20);
                return 1;
            }).when(adminPolicyMapper).insertCategory(any());

            AdminPolicyCateResDto result = adminPolicyService.createCategory(req);

            assertThat(result.getPolicyCategoryId()).isEqualTo(20);
            verifyNotification(AlarmType.ACTIVATE_POLICY, null, 20, "광고");
        }

        @Test
        void updateCategory_success() {
            Integer categoryId = 5;
            AdminCategoryReqDto req = new AdminCategoryReqDto();
            req.setPolicyCategoryName("수정카테고리");
            when(adminPolicyMapper.selectCategoryById(categoryId))
                    .thenReturn(AdminPolicyCateResDto.builder().policyCategoryId(categoryId).policyCategoryName("기존").build());
            when(adminPolicyMapper.updateCategory(eq(categoryId), any())).thenReturn(1);

            AdminPolicyCateResDto result = adminPolicyService.updateCategory(categoryId, req);

            assertThat(result.getPolicyCategoryName()).isEqualTo("수정카테고리");
            verifyNotification(AlarmType.ACTIVATE_POLICY, null, categoryId, "수정카테고리");
        }

        @Test
        void updateCategory_notFound_throws() {
            when(adminPolicyMapper.selectCategoryById(5)).thenReturn(null);

            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> adminPolicyService.updateCategory(5, new AdminCategoryReqDto()));

            assertThat(ex.getErrorCode()).isEqualTo(PolicyErrorCode.ADMIN_POLICY_NOT_FOUND);
        }

        @Test
        void deleteCategory_success() {
            Integer categoryId = 5;
            when(adminPolicyMapper.selectCategoryById(categoryId))
                    .thenReturn(AdminPolicyCateResDto.builder().policyCategoryId(categoryId).policyCategoryName("삭제카테고리").build());
            when(adminPolicyMapper.deleteCategory(categoryId)).thenReturn(1);

            AdminPolicyCateResDto result = adminPolicyService.deleteCategory(categoryId);

            assertThat(result.getPolicyCategoryId()).isEqualTo(categoryId);
            verifyNotification(AlarmType.DEACTIVATE_POLICY, null, categoryId, "삭제카테고리");
        }
    }

    private void verifyNotification(AlarmType type, Integer policyId, Integer categoryId, String name) {
        ArgumentCaptor<NotiSendReqDto> captor = ArgumentCaptor.forClass(NotiSendReqDto.class);
        verify(alarmHistoryService, atLeastOnce()).sendNotification(captor.capture());

        NotiSendReqDto req = captor.getValue();
        assertThat(req.getTargetType()).isEqualTo(NotificationTargetType.OWNER);

        JsonNode value = req.getValue();
        assertThat(value.get("type").asText()).isEqualTo(type.name());
        if (policyId != null) {
            assertThat(value.get("policyId").asInt()).isEqualTo(policyId);
        }
        if (categoryId != null) {
            assertThat(value.get("policyCategoryId").asInt()).isEqualTo(categoryId);
        }
        if (name != null) {
            assertThat(value.get("name").asText()).isEqualTo(name);
        }
    }
}
