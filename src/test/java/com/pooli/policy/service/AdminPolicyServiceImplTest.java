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
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockRehydrateAllResDto;
import com.pooli.policy.exception.PolicyErrorCode;
import com.pooli.policy.mapper.AdminPolicyMapper;
import com.pooli.policy.mapper.RepeatBlockMapper;
import com.pooli.traffic.service.outbox.PolicySyncResult;
import com.pooli.traffic.service.policy.TrafficPolicyWriteThroughService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminPolicyServiceImplTest {

    @Mock
    private AdminPolicyMapper adminPolicyMapper;
    @Mock
    private AlarmHistoryService alarmHistoryService;
    @Mock
    private PolicyHistoryService policyHistoryService;
    @Mock
    private ObjectProvider<TrafficPolicyWriteThroughService> trafficPolicyWriteThroughServiceProvider;
    @Mock
    private TrafficPolicyWriteThroughService trafficPolicyWriteThroughService;
    @Mock
    private RepeatBlockMapper repeatBlockMapper;
    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;
    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @InjectMocks
    private AdminPolicyServiceImpl adminPolicyService;

    @Nested
    @DisplayName("조회 메서드")
    class ReadMethods {

        @Test
        @DisplayName("전체 정책 목록 조회 시 매퍼 결과를 그대로 반환한다")
        void getAllPolicies_success() {
            // given
            when(adminPolicyMapper.selectAllPolicies()).thenReturn(List.of(
                    AdminPolicyResDto.builder().policyId(1).policyName("P1").build()
            ));

            // when
            List<AdminPolicyResDto> result = adminPolicyService.getAllPolicies();

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPolicyName()).isEqualTo("P1");
        }

        @Test
        @DisplayName("카테고리 목록 조회 시 매퍼 결과를 그대로 반환한다")
        void getCategories_success() {
            // given
            when(adminPolicyMapper.selectAllCategories()).thenReturn(List.of(
                    AdminPolicyCateResDto.builder().policyCategoryId(1).policyCategoryName("차단").build()
            ));

            // when
            List<AdminPolicyCateResDto> result = adminPolicyService.getCategories();

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPolicyCategoryName()).isEqualTo("차단");
        }
    }

    @Nested
    @DisplayName("쓰기 메서드")
    class WriteMethods {

        @Test
        @DisplayName("정책 생성 성공 시 생성된 정책 ID를 포함해 반환한다")
        void createPolicy_success() {
            // given
            AdminPolicyReqDto req = new AdminPolicyReqDto();
            req.setPolicyName("신규 정책");
            req.setPolicyCategoryId(1);
            doAnswer(inv -> {
                AdminPolicyReqDto arg = inv.getArgument(0);
                arg.setPolicyId(10);
                return 1;
            }).when(adminPolicyMapper).insertPolicy(any());
            when(adminPolicyMapper.selectPolicyById(10)).thenReturn(
                    AdminPolicyResDto.builder()
                            .policyId(10)
                            .policyName("신규 정책")
                            .policyCategoryId(1)
                            .build()
            );

            // when
            AdminPolicyResDto result = adminPolicyService.createPolicy(req);

            // then
            assertThat(result.getPolicyId()).isEqualTo(10);
            verify(adminPolicyMapper).insertPolicy(req);
        }

        @Test
        @DisplayName("정책 수정 성공 시 수정값이 반영된 DTO를 반환한다")
        void updatePolicy_success() {
            // given
            Integer policyId = 1;
            AdminPolicyReqDto req = new AdminPolicyReqDto();
            req.setPolicyName("수정 정책");
            req.setPolicyCategoryId(5);
            req.setIsActive(true);
            when(adminPolicyMapper.selectPolicyById(policyId))
                    .thenReturn(
                            AdminPolicyResDto.builder().policyId(policyId).policyName("기존").build(),
                            AdminPolicyResDto.builder().policyId(policyId).policyName("수정 정책").policyCategoryId(5).build()
                    );
            when(adminPolicyMapper.updatePolicy(eq(policyId), any())).thenReturn(1);

            // when
            AdminPolicyResDto result = adminPolicyService.updatePolicy(policyId, req);

            // then
            assertThat(result.getPolicyName()).isEqualTo("수정 정책");
            verify(adminPolicyMapper).updatePolicy(policyId, req);
        }

        @Test
        @DisplayName("존재하지 않는 정책 수정 요청 시 ADMIN_POLICY_NOT_FOUND를 던진다")
        void updatePolicy_notFound_throws() {
            // given
            when(adminPolicyMapper.selectPolicyById(1)).thenReturn(null);

            // when
            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> adminPolicyService.updatePolicy(1, new AdminPolicyReqDto()));

            // then
            assertThat(ex.getErrorCode()).isEqualTo(PolicyErrorCode.ADMIN_POLICY_NOT_FOUND);
        }

        @Test
        @DisplayName("정책 삭제 성공 시 삭제된 정책 ID를 반환한다")
        void deletePolicy_success() {
            // given
            Integer policyId = 2;
            when(adminPolicyMapper.selectPolicyById(policyId))
                    .thenReturn(AdminPolicyResDto.builder().policyId(policyId).policyName("삭제정책").build());
            when(adminPolicyMapper.deletePolicy(policyId)).thenReturn(1);

            // when
            AdminPolicyResDto result = adminPolicyService.deletePolicy(policyId);

            // then
            assertThat(result.getPolicyId()).isEqualTo(policyId);
            verify(adminPolicyMapper).deletePolicy(policyId);
        }

        @Test
        @DisplayName("정책 활성화 변경 성공 시 활성화 알림과 결과를 반환한다")
        void updateActivationPolicy_success() {
            // given
            Integer policyId = 3;
            AdminPolicyActiveReqDto req = new AdminPolicyActiveReqDto();
            req.setIsActive(true);
            when(adminPolicyMapper.selectPolicyById(policyId)).thenReturn(
                    AdminPolicyResDto.builder().policyId(policyId).policyName("A").policyCategoryId(7).build());
            when(adminPolicyMapper.updatePolicyActiveStatus(eq(policyId), any())).thenReturn(1);

            // when
            AdminPolicyActiveResDto result = adminPolicyService.updateActivationPolicy(policyId, req);

            // then
            assertThat(result.getIsActive()).isTrue();
            verifyNotification(AlarmType.ACTIVATE_POLICY, policyId, 7, "A");
        }

        @Test
        @DisplayName("카테고리 생성 성공 시 생성된 카테고리 ID를 반환한다")
        void createCategory_success() {
            // given
            AdminCategoryReqDto req = new AdminCategoryReqDto();
            req.setPolicyCategoryName("광고");
            doAnswer(inv -> {
                AdminCategoryReqDto arg = inv.getArgument(0);
                arg.setPolicyCategoryId(20);
                return 1;
            }).when(adminPolicyMapper).insertCategory(any());
            when(adminPolicyMapper.selectCategoryById(20)).thenReturn(
                    AdminPolicyCateResDto.builder()
                            .policyCategoryId(20)
                            .policyCategoryName("광고")
                            .build()
            );

            // when
            AdminPolicyCateResDto result = adminPolicyService.createCategory(req);

            // then
            assertThat(result.getPolicyCategoryId()).isEqualTo(20);
        }

        @Test
        @DisplayName("카테고리 수정 성공 시 수정된 카테고리명을 반환한다")
        void updateCategory_success() {
            // given
            Integer categoryId = 5;
            AdminCategoryReqDto req = new AdminCategoryReqDto();
            req.setPolicyCategoryName("수정카테고리");
            when(adminPolicyMapper.selectCategoryById(categoryId))
                    .thenReturn(
                            AdminPolicyCateResDto.builder().policyCategoryId(categoryId).policyCategoryName("기존").build(),
                            AdminPolicyCateResDto.builder().policyCategoryId(categoryId).policyCategoryName("수정카테고리").build()
                    );
            when(adminPolicyMapper.updateCategory(eq(categoryId), any())).thenReturn(1);

            // when
            AdminPolicyCateResDto result = adminPolicyService.updateCategory(categoryId, req);

            // then
            assertThat(result.getPolicyCategoryName()).isEqualTo("수정카테고리");
        }

        @Test
        @DisplayName("존재하지 않는 카테고리 수정 요청 시 ADMIN_POLICY_NOT_FOUND를 던진다")
        void updateCategory_notFound_throws() {
            // given
            when(adminPolicyMapper.selectCategoryById(5)).thenReturn(null);

            // when
            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> adminPolicyService.updateCategory(5, new AdminCategoryReqDto()));

            // then
            assertThat(ex.getErrorCode()).isEqualTo(PolicyErrorCode.ADMIN_POLICY_NOT_FOUND);
        }

        @Test
        @DisplayName("카테고리 삭제 성공 시 삭제된 카테고리 ID를 반환한다")
        void deleteCategory_success() {
            // given
            Integer categoryId = 5;
            when(adminPolicyMapper.selectCategoryById(categoryId))
                    .thenReturn(AdminPolicyCateResDto.builder().policyCategoryId(categoryId).policyCategoryName("삭제카테고리").build());
            when(adminPolicyMapper.deleteCategory(categoryId)).thenReturn(1);

            // when
            AdminPolicyCateResDto result = adminPolicyService.deleteCategory(categoryId);

            // then
            assertThat(result.getPolicyCategoryId()).isEqualTo(categoryId);
        }

        @Test
        @DisplayName("repeat block Redis 전체 재적재 시 성공/실패 집계를 반환한다")
        void rehydrateAllRepeatBlocksToRedis_returnsSummary() {
            // given
            when(trafficPolicyWriteThroughServiceProvider.getIfAvailable()).thenReturn(trafficPolicyWriteThroughService);
            when(trafficRedisKeyFactory.repeatBlockKeyPattern()).thenReturn("pooli:repeat_block:*");
            when(trafficRedisKeyFactory.repeatBlockKeyPrefix()).thenReturn("pooli:repeat_block:");
            when(cacheStringRedisTemplate.keys("pooli:repeat_block:*"))
                    .thenReturn(Set.of("pooli:repeat_block:11", "pooli:repeat_block:12"));
            when(repeatBlockMapper.selectRepeatBlocksByLineId(11L)).thenReturn(List.of(RepeatBlockPolicyResDto.builder().build()));
            when(repeatBlockMapper.selectRepeatBlocksByLineId(12L)).thenReturn(List.of(RepeatBlockPolicyResDto.builder().build()));
            when(trafficPolicyWriteThroughService.syncRepeatBlockUntracked(eq(11L), any(), anyLong()))
                    .thenReturn(PolicySyncResult.SUCCESS);
            when(trafficPolicyWriteThroughService.syncRepeatBlockUntracked(eq(12L), any(), anyLong()))
                    .thenReturn(PolicySyncResult.CONNECTION_FAILURE);

            // when
            RepeatBlockRehydrateAllResDto result = adminPolicyService.rehydrateAllRepeatBlocksToRedis();

            // then
            assertThat(result.getTotalLineCount()).isEqualTo(2);
            assertThat(result.getSuccessCount()).isEqualTo(1);
            assertThat(result.getFailureCount()).isEqualTo(1);
            assertThat(result.getFailedLineIds()).containsExactlyInAnyOrder(12L);
            verify(trafficPolicyWriteThroughService, times(2)).syncRepeatBlockUntracked(anyLong(), any(), anyLong());
        }

        @Test
        @DisplayName("유효하지 않은 repeat block 키는 재적재 대상에서 제외한다")
        void rehydrateAllRepeatBlocksToRedis_skipsInvalidKeys() {
            // given
            when(trafficPolicyWriteThroughServiceProvider.getIfAvailable()).thenReturn(trafficPolicyWriteThroughService);
            when(trafficRedisKeyFactory.repeatBlockKeyPattern()).thenReturn("pooli:repeat_block:*");
            when(trafficRedisKeyFactory.repeatBlockKeyPrefix()).thenReturn("pooli:repeat_block:");
            when(cacheStringRedisTemplate.keys("pooli:repeat_block:*"))
                    .thenReturn(Set.of("pooli:repeat_block:abc", "pooli:repeat_block:11"));
            when(repeatBlockMapper.selectRepeatBlocksByLineId(11L)).thenReturn(List.of());
            when(trafficPolicyWriteThroughService.syncRepeatBlockUntracked(eq(11L), any(), anyLong()))
                    .thenReturn(PolicySyncResult.SUCCESS);

            // when
            RepeatBlockRehydrateAllResDto result = adminPolicyService.rehydrateAllRepeatBlocksToRedis();

            // then
            assertThat(result.getTotalLineCount()).isEqualTo(1);
            assertThat(result.getSuccessCount()).isEqualTo(1);
            assertThat(result.getFailureCount()).isEqualTo(0);
            assertThat(result.getFailedLineIds()).isEmpty();
            verify(repeatBlockMapper, times(1)).selectRepeatBlocksByLineId(11L);
        }

        @Test
        @DisplayName("write-through 서비스가 없으면 전체 재적재 요청을 실패시킨다")
        void rehydrateAllRepeatBlocksToRedis_withoutWriteThrough_throws() {
            // given
            when(trafficPolicyWriteThroughServiceProvider.getIfAvailable()).thenReturn(null);

            // when
            ApplicationException ex = assertThrows(
                    ApplicationException.class,
                    () -> adminPolicyService.rehydrateAllRepeatBlocksToRedis()
            );

            // then
            assertThat(ex.getMessage()).contains("cache Redis profile");
        }
    }

    /**
     * 알림 전송 공통 검증 헬퍼.
     *
     * @param type 기대하는 알림 타입(필수)
     * @param policyId 정책 ID 검증값(없으면 null)
     * @param categoryId 정책 카테고리 ID 검증값(없으면 null)
     * @param name 알림 payload 내 이름 검증값(없으면 null)
     */
    private void verifyNotification(AlarmType type, Integer policyId, Integer categoryId, String name) {
        ArgumentCaptor<NotiSendReqDto> captor = ArgumentCaptor.forClass(NotiSendReqDto.class);
        // 테스트 대상 메서드에서 알림 전송이 실제로 호출되었는지 확인하고 payload를 캡처한다.
        verify(alarmHistoryService, atLeastOnce()).sendNotificationAsync(captor.capture());

        NotiSendReqDto req = captor.getValue();
        // 관리자 정책 변경 알림은 OWNER 대상으로만 전송되어야 한다.
        assertThat(req.getTargetType()).isEqualTo(NotificationTargetType.OWNER);

        JsonNode value = req.getValue();
        // type은 항상 포함되어야 하는 필수 필드다.
        assertThat(value.get("type").asText()).isEqualTo(type.name());
        // 선택 필드는 null이 아닐 때만 검증한다(테스트 케이스별 payload 구조 차이 허용).
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
