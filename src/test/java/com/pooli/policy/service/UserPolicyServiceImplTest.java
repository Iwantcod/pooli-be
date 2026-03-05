package com.pooli.policy.service;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.dto.PagingResDto;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.service.AlarmHistoryService;
import com.pooli.permission.mapper.FamilyLineMapper;
import com.pooli.policy.domain.dto.request.*;
import com.pooli.policy.domain.dto.response.*;
import com.pooli.policy.domain.entity.AppPolicy;
import com.pooli.policy.domain.entity.LineLimit;
import com.pooli.policy.domain.enums.DayOfWeek;
import com.pooli.policy.domain.enums.PolicyScope;
import com.pooli.policy.domain.enums.SortType;
import com.pooli.policy.exception.PolicyErrorCode;
import com.pooli.policy.mapper.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserPolicyServiceImplTest {

    @Mock private AlarmHistoryService alarmHistoryService;
    @Mock private FamilyLineMapper familyLineMapper;
    @Mock private LineLimitMapper lineLimitMapper;
    @Mock private AppPolicyMapper appPolicyMapper;
    @Mock private PolicyBackOfficeMapper policyBackOfficeMapper;
    @Mock private RepeatBlockMapper repeatBlockMapper;
    @Mock private RepeatBlockDayMapper repeatBlockDayMapper;
    @Mock private ImmediateBlockMapper immediateBlockMapper;

    @InjectMocks
    private UserPolicyServiceImpl userPolicyService;

    private AuthUserDetails userAuth(Long lineId) {
        return AuthUserDetails.builder()
                .userId(1L)
                .lineId(lineId)
                .email("user@test.com")
                .authorities(AuthUserDetails.toAuthorities(List.of("ROLE_USER")))
                .build();
    }

    private void allowFamily(Long myLineId, Long targetLineId) {
        when(familyLineMapper.findAllFamilyIdByLineId(myLineId)).thenReturn(List.of(myLineId, targetLineId));
    }

    @Nested
    @DisplayName("조회 메서드")
    class ReadMethods {

        @Test
        void getActivePolicies_success() {
            ActivePolicyResDto dto = ActivePolicyResDto.builder().policyId(1L).policyName("P1").build();
            when(policyBackOfficeMapper.selectActivePolicies()).thenReturn(List.of(dto));

            List<ActivePolicyResDto> result = userPolicyService.getActivePolicies();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPolicyName()).isEqualTo("P1");
        }

        @Test
        void getRepeatBlockPolicies_success() {
            Long lineId = 100L;
            allowFamily(100L, lineId);
            when(repeatBlockMapper.selectRepeatBlocksByLineId(lineId))
                    .thenReturn(List.of(RepeatBlockPolicyResDto.builder().repeatBlockId(10L).lineId(lineId).build()));

            List<RepeatBlockPolicyResDto> result = userPolicyService.getRepeatBlockPolicies(lineId, userAuth(100L));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRepeatBlockId()).isEqualTo(10L);
        }

        @Test
        void getImmediateBlockPolicy_whenMissing_returnsNullEndAt() {
            Long lineId = 100L;
            allowFamily(100L, lineId);
            when(immediateBlockMapper.selectImmediateBlockPolicy(lineId)).thenReturn(null);

            ImmediateBlockResDto result = userPolicyService.getImmediateBlockPolicy(lineId, userAuth(100L));

            assertThat(result.getLineId()).isEqualTo(lineId);
            assertThat(result.getBlockEndAt()).isNull();
        }

        @Test
        void getLimitPolicy_whenMissing_returnsEmptyDto() {
            Long lineId = 100L;
            allowFamily(100L, lineId);
            when(lineLimitMapper.getExistLineLimitByLineId(lineId)).thenReturn(Optional.empty());

            LimitPolicyResDto result = userPolicyService.getLimitPolicy(lineId, userAuth(100L));

            assertThat(result.getLineLimitId()).isNull();
            assertThat(result.getDailyDataLimit()).isNull();
        }

        @Test
        void getAppPolicies_success_appliesDefaultsAndPaging() {
            Long lineId = 100L;
            allowFamily(100L, lineId);
            AppPolicySearchCondReqDto req = AppPolicySearchCondReqDto.builder()
                    .lineId(lineId)
                    .keyword("   ")
                    .pageNumber(0)
                    .pageSize(2)
                    .build();

            when(appPolicyMapper.findApplicationsWithPolicy(any())).thenReturn(List.of(
                    AppPolicyResDto.builder().appPolicyId(1L).lineId(lineId).build(),
                    AppPolicyResDto.builder().appPolicyId(2L).lineId(lineId).build()
            ));
            when(appPolicyMapper.countApplicationsWithPolicy(any())).thenReturn(5L);

            PagingResDto<AppPolicyResDto> result = userPolicyService.getAppPolicies(req, userAuth(100L));

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getTotalElements()).isEqualTo(5L);
            assertThat(result.getTotalPages()).isEqualTo(3);

            ArgumentCaptor<AppPolicySearchCondReqDto> captor = ArgumentCaptor.forClass(AppPolicySearchCondReqDto.class);
            verify(appPolicyMapper).findApplicationsWithPolicy(captor.capture());
            assertThat(captor.getValue().getPolicyScope()).isEqualTo(PolicyScope.ALL);
            assertThat(captor.getValue().getSortType()).isEqualTo(SortType.ACTIVE);
            assertThat(captor.getValue().getKeyword()).isNull();
        }

        @Test
        void getAppliedPolicies_success() {
            Long lineId = 100L;
            allowFamily(100L, lineId);
            when(repeatBlockMapper.selectRepeatBlocksByLineId(lineId))
                    .thenReturn(List.of(RepeatBlockPolicyResDto.builder().repeatBlockId(1L).lineId(lineId).build()));
            when(immediateBlockMapper.selectImmediateBlockPolicy(lineId))
                    .thenReturn(ImmediateBlockResDto.builder().lineId(lineId).blockEndAt(LocalDateTime.now()).build());

            AppliedPolicyResDto result = userPolicyService.getAppliedPolicies(lineId, userAuth(100L));

            assertThat(result.getRepeatBlockPolicyList()).hasSize(1);
            assertThat(result.getImmediateBlock()).isNotNull();
        }

        @Test
        void getAppPolicies_invalidPageNumber_throws() {
            AppPolicySearchCondReqDto req = AppPolicySearchCondReqDto.builder()
                    .lineId(100L)
                    .pageNumber(-1)
                    .pageSize(10)
                    .build();

            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.getAppPolicies(req, userAuth(100L)));

            assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_PAGE_NUMBER);
        }
    }

    @Nested
    @DisplayName("쓰기 메서드")
    class WriteMethods {

        @Test
        void createRepeatBlockPolicy_success() {
            Long lineId = 100L;
            allowFamily(100L, lineId);

            RepeatBlockDayReqDto day = new RepeatBlockDayReqDto();
            day.setDayOfWeek(DayOfWeek.MON);
            day.setStartAt(LocalTime.of(9, 0));
            day.setEndAt(LocalTime.of(18, 0));

            RepeatBlockPolicyReqDto req = new RepeatBlockPolicyReqDto();
            req.setDays(List.of(day));
            req.setIsActive(true);

            when(repeatBlockMapper.isDuplicatedRepeatBlocks(eq(lineId), any(), any(), any())).thenReturn(false);
            doAnswer(inv -> {
                RepeatBlockPolicyReqDto arg = inv.getArgument(0);
                arg.setRepeatBlockId(11L);
                return 1;
            }).when(repeatBlockMapper).insertRepeatBlock(any());

            RepeatBlockPolicyResDto result = userPolicyService.createRepeatBlockPolicy(req, userAuth(100L));

            assertThat(result.getRepeatBlockId()).isEqualTo(11L);
            verify(repeatBlockDayMapper).insertRepeatBlockDays(eq(11L), any());
            verify(alarmHistoryService).createAlarm(lineId, AlarmCode.POLICY_LIMIT, AlarmType.CREATE_REPEAT_BLOCK);
        }

        @Test
        void createRepeatBlockPolicy_whenConflict_throws() {
            Long lineId = 100L;
            allowFamily(100L, lineId);

            RepeatBlockDayReqDto day = new RepeatBlockDayReqDto();
            day.setDayOfWeek(DayOfWeek.MON);
            day.setStartAt(LocalTime.of(9, 0));
            day.setEndAt(LocalTime.of(18, 0));

            RepeatBlockPolicyReqDto req = new RepeatBlockPolicyReqDto();
            req.setDays(List.of(day));

            when(repeatBlockMapper.isDuplicatedRepeatBlocks(eq(lineId), any(), any(), any())).thenReturn(true);

            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.createRepeatBlockPolicy(req, userAuth(100L)));

            assertThat(ex.getErrorCode()).isEqualTo(PolicyErrorCode.BLOCK_POLICY_CONFLICT);
        }

        @Test
        void updateRepeatBlockPolicy_whenNotFound_throws() {
            when(repeatBlockMapper.selectRepeatBlockById(1L)).thenReturn(null);

            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.updateRepeatBlockPolicy(1L, new RepeatBlockPolicyReqDto(), userAuth(100L)));

            assertThat(ex.getErrorCode()).isEqualTo(PolicyErrorCode.REPEAT_BLOCK_NOT_FOUND);
        }

        @Test
        void updateRepeatBlockPolicy_success() {
            Long lineId = 100L;
            allowFamily(100L, lineId);
            when(repeatBlockMapper.selectRepeatBlockById(1L))
                    .thenReturn(RepeatBlockPolicyResDto.builder().repeatBlockId(1L).lineId(lineId).build());

            RepeatBlockDayReqDto day = new RepeatBlockDayReqDto();
            day.setDayOfWeek(DayOfWeek.TUE);
            day.setStartAt(LocalTime.of(8, 0));
            day.setEndAt(LocalTime.of(9, 0));

            RepeatBlockPolicyReqDto req = new RepeatBlockPolicyReqDto();
            req.setIsActive(true);
            req.setDays(List.of(day));

            when(repeatBlockMapper.isDuplicatedRepeatBlocks(eq(lineId), any(), any(), any())).thenReturn(false);
            doAnswer(inv -> {
                RepeatBlockPolicyReqDto arg = inv.getArgument(0);
                arg.setRepeatBlockId(22L);
                return 1;
            }).when(repeatBlockMapper).insertRepeatBlock(any());

            RepeatBlockPolicyResDto result = userPolicyService.updateRepeatBlockPolicy(1L, req, userAuth(100L));

            assertThat(result.getRepeatBlockId()).isEqualTo(22L);
            verify(repeatBlockMapper).deleteRepeatBlock(1L);
            verify(alarmHistoryService).createAlarm(lineId, AlarmCode.POLICY_LIMIT, AlarmType.UPDATE_REPEAT_BLOCK);
        }

        @Test
        void deleteRepeatBlockPolicy_success() {
            Long lineId = 100L;
            allowFamily(100L, lineId);
            when(repeatBlockMapper.selectRepeatBlockById(7L))
                    .thenReturn(RepeatBlockPolicyResDto.builder().repeatBlockId(7L).lineId(lineId).build());

            RepeatBlockPolicyResDto result = userPolicyService.deleteRepeatBlockPolicy(7L, userAuth(100L));

            assertThat(result.getRepeatBlockId()).isEqualTo(7L);
            assertThat(result.getIsActive()).isFalse();
        }

        @Test
        void updateImmediateBlockPolicy_success() {
            Long lineId = 100L;
            allowFamily(100L, lineId);
            ImmediateBlockReqDto req = new ImmediateBlockReqDto();
            req.setBlockEndAt(LocalDateTime.of(2026, 3, 5, 10, 0));

            ImmediateBlockResDto result = userPolicyService.updateImmediateBlockPolicy(lineId, req, userAuth(100L));

            assertThat(result.getBlockEndAt()).isEqualTo(req.getBlockEndAt());
            verify(immediateBlockMapper).updateImmediateBlockPolicy(lineId, req);
            verify(alarmHistoryService).createAlarm(lineId, AlarmCode.POLICY_LIMIT, AlarmType.UPDATE_IMMEDIATE_BLOCK);
        }

        @Test
        void toggleDailyTotalLimitPolicy_existing_success() {
            Long lineId = 100L;
            allowFamily(100L, lineId);
            LineLimit lineLimit = LineLimit.builder()
                    .limitId(1L)
                    .lineId(lineId)
                    .dailyDataLimit(1000L)
                    .isDailyLimitActive(false)
                    .sharedDataLimit(5000L)
                    .isSharedLimitActive(true)
                    .build();
            when(lineLimitMapper.getExistLineLimitByLineId(lineId)).thenReturn(Optional.of(lineLimit));
            when(lineLimitMapper.updateIsDailyLimitActiveById(1L, true)).thenReturn(1);

            LimitPolicyResDto result = userPolicyService.toggleDailyTotalLimitPolicy(lineId, userAuth(100L));

            assertThat(result.getIsDailyDataLimitActive()).isTrue();
            verify(alarmHistoryService).createAlarm(lineId, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_CREATE_DAYDATA_LIMIT);
        }

        @Test
        void toggleDailyTotalLimitPolicy_dbFail_throws() {
            Long lineId = 100L;
            allowFamily(100L, lineId);
            LineLimit lineLimit = LineLimit.builder().limitId(1L).lineId(lineId).isDailyLimitActive(true).build();
            when(lineLimitMapper.getExistLineLimitByLineId(lineId)).thenReturn(Optional.of(lineLimit));
            when(lineLimitMapper.updateIsDailyLimitActiveById(1L, false)).thenReturn(0);

            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.toggleDailyTotalLimitPolicy(lineId, userAuth(100L)));

            assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.DATABASE_ERROR);
        }

        @Test
        void toggleDailyTotalLimitPolicy_whenMissing_insertsNew() {
            Long lineId = 100L;
            allowFamily(100L, lineId);
            when(lineLimitMapper.getExistLineLimitByLineId(lineId)).thenReturn(Optional.empty());
            doAnswer(inv -> {
                LineLimit ll = inv.getArgument(0);
                java.lang.reflect.Field field = LineLimit.class.getDeclaredField("limitId");
                field.setAccessible(true);
                field.set(ll, 99L);
                return 1;
            }).when(lineLimitMapper).createLineLimit(any());

            LimitPolicyResDto result = userPolicyService.toggleDailyTotalLimitPolicy(lineId, userAuth(100L));

            assertThat(result.getDailyDataLimit()).isEqualTo(-1L);
            assertThat(result.getIsSharedDataLimitActive()).isTrue();
        }

        @Test
        void updateDailyTotalLimitPolicyValue_success() {
            LimitPolicyUpdateReqDto req = new LimitPolicyUpdateReqDto();
            req.setLimitPolicyId(1L);
            req.setPolicyValue(777L);

            LineLimit lineLimit = LineLimit.builder()
                    .limitId(1L)
                    .lineId(100L)
                    .dailyDataLimit(100L)
                    .isDailyLimitActive(true)
                    .sharedDataLimit(500L)
                    .isSharedLimitActive(false)
                    .build();
            when(lineLimitMapper.getExistLineLimitById(1L)).thenReturn(Optional.of(lineLimit));
            allowFamily(100L, 100L);
            when(lineLimitMapper.updateDailyDataLimit(req)).thenReturn(1);

            LimitPolicyResDto result = userPolicyService.updateDailyTotalLimitPolicyValue(req, userAuth(100L));

            assertThat(result.getDailyDataLimit()).isEqualTo(777L);
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_CHANGE, AlarmType.POLICY_UPDATE_DAYDATA_LIMIT);
        }

        @Test
        void updateDailyTotalLimitPolicyValue_notFound_throws() {
            LimitPolicyUpdateReqDto req = new LimitPolicyUpdateReqDto();
            req.setLimitPolicyId(1L);
            when(lineLimitMapper.getExistLineLimitById(1L)).thenReturn(Optional.empty());

            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.updateDailyTotalLimitPolicyValue(req, userAuth(100L)));

            assertThat(ex.getErrorCode()).isEqualTo(PolicyErrorCode.LIMIT_POLICY_NOT_FOUND);
        }

        @Test
        void toggleSharedPoolLimitPolicy_existing_success() {
            Long lineId = 100L;
            allowFamily(100L, lineId);
            LineLimit lineLimit = LineLimit.builder()
                    .limitId(10L)
                    .lineId(lineId)
                    .dailyDataLimit(1000L)
                    .isDailyLimitActive(true)
                    .sharedDataLimit(3000L)
                    .isSharedLimitActive(false)
                    .build();
            when(lineLimitMapper.getExistLineLimitByLineId(lineId)).thenReturn(Optional.of(lineLimit));
            when(lineLimitMapper.updateIsSharedLimitActiveById(10L, true)).thenReturn(1);

            LimitPolicyResDto result = userPolicyService.toggleSharedPoolLimitPolicy(lineId, userAuth(100L));

            assertThat(result.getIsSharedDataLimitActive()).isTrue();
            verify(alarmHistoryService).createAlarm(lineId, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_CREATE_SHAREDATA_LIMIT);
        }

        @Test
        void updateSharedPoolLimitPolicyValue_success() {
            LimitPolicyUpdateReqDto req = new LimitPolicyUpdateReqDto();
            req.setLimitPolicyId(10L);
            req.setPolicyValue(2222L);

            LineLimit lineLimit = LineLimit.builder()
                    .limitId(10L)
                    .lineId(100L)
                    .dailyDataLimit(1000L)
                    .isDailyLimitActive(true)
                    .sharedDataLimit(3333L)
                    .isSharedLimitActive(true)
                    .build();
            when(lineLimitMapper.getExistLineLimitById(10L)).thenReturn(Optional.of(lineLimit));
            allowFamily(100L, 100L);
            when(lineLimitMapper.updateSharedDataLimit(req)).thenReturn(1);

            LimitPolicyResDto result = userPolicyService.updateSharedPoolLimitPolicyValue(req, userAuth(100L));

            assertThat(result.getSharedDataLimit()).isEqualTo(2222L);
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_CHANGE, AlarmType.POLICY_UPDATE_SHAREDATA_LIMIT);
        }

        @Test
        void updateAppDataLimit_success() {
            AppDataLimitUpdateReqDto req = new AppDataLimitUpdateReqDto();
            req.setAppPolicyId(1L);
            req.setValue(5000L);

            AppPolicyResDto appPolicy = AppPolicyResDto.builder()
                    .appPolicyId(1L)
                    .lineId(100L)
                    .dailyLimitData(100L)
                    .dailyLimitSpeed(50)
                    .isActive(true)
                    .isWhiteList(false)
                    .build();
            when(appPolicyMapper.findDtoExistById(1L)).thenReturn(Optional.of(appPolicy));
            allowFamily(100L, 100L);
            when(appPolicyMapper.updateDataLimit(1L, 5000L)).thenReturn(1);

            AppPolicyResDto result = userPolicyService.updateAppDataLimit(req, userAuth(100L));

            assertThat(result.getDailyLimitData()).isEqualTo(5000L);
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_CHANGE, AlarmType.POLICY_UPDATE_APP_USAGE_LIMIT);
        }

        @Test
        void updateAppDataLimit_dbFail_throws() {
            AppDataLimitUpdateReqDto req = new AppDataLimitUpdateReqDto();
            req.setAppPolicyId(1L);
            req.setValue(5000L);

            AppPolicyResDto appPolicy = AppPolicyResDto.builder().appPolicyId(1L).lineId(100L).build();
            when(appPolicyMapper.findDtoExistById(1L)).thenReturn(Optional.of(appPolicy));
            allowFamily(100L, 100L);
            when(appPolicyMapper.updateDataLimit(1L, 5000L)).thenReturn(0);

            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.updateAppDataLimit(req, userAuth(100L)));

            assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.DATABASE_ERROR);
        }

        @Test
        void updateAppSpeedLimit_success() {
            AppSpeedLimitUpdateReqDto req = new AppSpeedLimitUpdateReqDto();
            req.setAppPolicyId(2L);
            req.setValue(70);

            AppPolicyResDto appPolicy = AppPolicyResDto.builder()
                    .appPolicyId(2L)
                    .lineId(100L)
                    .dailyLimitData(100L)
                    .dailyLimitSpeed(50)
                    .isActive(true)
                    .isWhiteList(false)
                    .build();
            when(appPolicyMapper.findDtoExistById(2L)).thenReturn(Optional.of(appPolicy));
            allowFamily(100L, 100L);
            when(appPolicyMapper.updateSpeedLimit(2L, 70)).thenReturn(1);

            AppPolicyResDto result = userPolicyService.updateAppSpeedLimit(req, userAuth(100L));

            assertThat(result.getDailyLimitSpeed()).isEqualTo(70);
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_CHANGE, AlarmType.POLICY_UPDATE_DATA_SPEED_LIMIT);
        }

        @Test
        void toggleAppPolicyActive_existingPolicy_success() {
            AppPolicyActiveToggleReqDto req = new AppPolicyActiveToggleReqDto();
            req.setLineId(100L);
            req.setApplicationId(10);
            allowFamily(100L, 100L);

            AppPolicyResDto appPolicy = AppPolicyResDto.builder()
                    .appPolicyId(1L)
                    .lineId(100L)
                    .appId(10)
                    .appName("YouTube")
                    .isActive(false)
                    .dailyLimitData(100L)
                    .dailyLimitSpeed(10)
                    .isWhiteList(false)
                    .build();
            when(appPolicyMapper.findDtoExistByLineIdAndAppId(100L, 10)).thenReturn(Optional.of(appPolicy));
            when(appPolicyMapper.updateIsActive(1L, true)).thenReturn(1);

            AppPolicyResDto result = userPolicyService.toggleAppPolicyActive(req, userAuth(100L));

            assertThat(result.getIsActive()).isTrue();
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_CREATE_APP_USAGE_LIMIT);
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_CREATE_DATA_SPEED_LIMIT);
        }

        @Test
        void toggleAppPolicyActive_newInsert_success() {
            AppPolicyActiveToggleReqDto req = new AppPolicyActiveToggleReqDto();
            req.setLineId(100L);
            req.setApplicationId(10);
            allowFamily(100L, 100L);

            AppPolicyResDto appBase = AppPolicyResDto.builder()
                    .appPolicyId(null)
                    .lineId(100L)
                    .appId(10)
                    .appName("YouTube")
                    .build();
            when(appPolicyMapper.findDtoExistByLineIdAndAppId(100L, 10)).thenReturn(Optional.of(appBase));
            doAnswer(inv -> {
                AppPolicy arg = inv.getArgument(0);
                java.lang.reflect.Field f = AppPolicy.class.getDeclaredField("appPolicyId");
                f.setAccessible(true);
                f.set(arg, 55L);
                return 1;
            }).when(appPolicyMapper).insertAppPolicy(any());

            AppPolicyResDto result = userPolicyService.toggleAppPolicyActive(req, userAuth(100L));

            assertThat(result.getAppPolicyId()).isEqualTo(55L);
            assertThat(result.getIsActive()).isTrue();
        }

        @Test
        void toggleAppPolicyActive_whenAppMissing_throws() {
            AppPolicyActiveToggleReqDto req = new AppPolicyActiveToggleReqDto();
            req.setLineId(100L);
            req.setApplicationId(10);
            allowFamily(100L, 100L);
            when(appPolicyMapper.findDtoExistByLineIdAndAppId(100L, 10)).thenReturn(Optional.empty());

            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.toggleAppPolicyActive(req, userAuth(100L)));

            assertThat(ex.getErrorCode()).isEqualTo(PolicyErrorCode.APP_NOT_FOUND);
        }

        @Test
        void toggleAppPolicyWhitelist_success() {
            AppPolicyResDto appPolicy = AppPolicyResDto.builder()
                    .appPolicyId(1L)
                    .lineId(100L)
                    .isWhiteList(false)
                    .build();
            when(appPolicyMapper.findDtoExistById(1L)).thenReturn(Optional.of(appPolicy));
            allowFamily(100L, 100L);
            when(appPolicyMapper.updateIsWhitelist(1L, true)).thenReturn(1);

            AppPolicyResDto result = userPolicyService.toggleAppPolicyWhitelist(1L, userAuth(100L));

            assertThat(result.getIsWhiteList()).isTrue();
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_ADD_WHITELIST);
        }

        @Test
        void deleteAppPolicy_success() {
            AppPolicy appPolicy = AppPolicy.builder().appPolicyId(1L).lineId(100L).build();
            when(appPolicyMapper.findEntityExistById(1L)).thenReturn(Optional.of(appPolicy));
            allowFamily(100L, 100L);
            when(appPolicyMapper.setDeleted(1L)).thenReturn(1);

            userPolicyService.deleteAppPolicy(1L, userAuth(100L));

            verify(appPolicyMapper).setDeleted(1L);
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_DELETE_APP_USAGE_LIMIT);
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_DELETE_DATA_SPEED_LIMIT);
        }

        @Test
        void deleteAppPolicy_notFound_throws() {
            when(appPolicyMapper.findEntityExistById(1L)).thenReturn(Optional.empty());

            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.deleteAppPolicy(1L, userAuth(100L)));

            assertThat(ex.getErrorCode()).isEqualTo(PolicyErrorCode.APP_POLICY_NOT_FOUND);
        }
    }
}
