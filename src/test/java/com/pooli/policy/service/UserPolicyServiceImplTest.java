package com.pooli.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.dto.PagingResDto;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.family.mapper.FamilyMapper;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.service.AlarmHistoryService;
import com.pooli.permission.mapper.FamilyLineMapper;
import com.pooli.policy.domain.dto.request.AppDataLimitUpdateReqDto;
import com.pooli.policy.domain.dto.request.AppPolicyActiveToggleReqDto;
import com.pooli.policy.domain.dto.request.AppPolicySearchCondReqDto;
import com.pooli.policy.domain.dto.request.AppSpeedLimitUpdateReqDto;
import com.pooli.policy.domain.dto.request.ImmediateBlockReqDto;
import com.pooli.policy.domain.dto.request.LimitPolicyUpdateReqDto;
import com.pooli.policy.domain.dto.request.RepeatBlockDayReqDto;
import com.pooli.policy.domain.dto.request.RepeatBlockPolicyReqDto;
import com.pooli.policy.domain.dto.response.ActivePolicyResDto;
import com.pooli.policy.domain.dto.response.AppPolicyResDto;
import com.pooli.policy.domain.dto.response.AppliedPolicyResDto;
import com.pooli.policy.domain.dto.response.ImmediateBlockResDto;
import com.pooli.policy.domain.dto.response.LimitPolicyResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import com.pooli.policy.domain.entity.AppPolicy;
import com.pooli.policy.domain.entity.LineLimit;
import com.pooli.policy.domain.enums.DayOfWeek;
import com.pooli.policy.domain.enums.PolicyScope;
import com.pooli.policy.domain.enums.SortType;
import com.pooli.policy.exception.PolicyErrorCode;
import com.pooli.policy.mapper.AppPolicyMapper;
import com.pooli.policy.mapper.ImmediateBlockMapper;
import com.pooli.policy.mapper.LineLimitMapper;
import com.pooli.policy.mapper.PolicyBackOfficeMapper;
import com.pooli.policy.mapper.RepeatBlockDayMapper;
import com.pooli.policy.mapper.RepeatBlockMapper;
import com.pooli.traffic.service.TrafficPolicyWriteThroughService;

@ExtendWith(MockitoExtension.class)
class UserPolicyServiceImplTest {

    @Mock private AlarmHistoryService alarmHistoryService;
    @Mock private FamilyLineMapper familyLineMapper;
    @Mock private LineLimitMapper lineLimitMapper;
    @Mock private AppPolicyMapper appPolicyMapper;
    @Mock private FamilyMapper familyMapper;
    @Mock private PolicyBackOfficeMapper policyBackOfficeMapper;
    @Mock private RepeatBlockMapper repeatBlockMapper;
    @Mock private RepeatBlockDayMapper repeatBlockDayMapper;
    @Mock private ImmediateBlockMapper immediateBlockMapper;
    @Mock private ObjectProvider<TrafficPolicyWriteThroughService> trafficPolicyWriteThroughServiceProvider;
    @Mock private ObjectProvider<PolicyWriteAuditService> policyWriteAuditServiceProvider;
    @Mock private PolicyWriteAuditService policyWriteAuditService;

    private UserPolicyServiceImpl userPolicyService;

    @BeforeEach
    void setUp() {
        userPolicyService = new UserPolicyServiceImpl(
                alarmHistoryService,
                familyLineMapper,
                lineLimitMapper,
                appPolicyMapper,
                familyMapper,
                policyBackOfficeMapper,
                repeatBlockMapper,
                repeatBlockDayMapper,
                immediateBlockMapper,
                trafficPolicyWriteThroughServiceProvider,
                policyWriteAuditServiceProvider
        );
        lenient().when(trafficPolicyWriteThroughServiceProvider.getIfAvailable()).thenReturn(null);
        lenient().when(policyWriteAuditServiceProvider.getIfAvailable()).thenReturn(null);
    }

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
        @DisplayName("활성화된 정책 목록 조회 시 백오피스 조회 결과를 반환한다")
        void getActivePolicies_success() {
            // given
            ActivePolicyResDto dto = ActivePolicyResDto.builder().policyId(1L).policyName("P1").build();
            when(policyBackOfficeMapper.selectActivePolicies()).thenReturn(List.of(dto));

            // when
            List<ActivePolicyResDto> result = userPolicyService.getActivePolicies();

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPolicyName()).isEqualTo("P1");
        }

        @Test
        @DisplayName("반복 차단 정책 조회 시 대상 라인의 정책 목록을 반환한다")
        void getRepeatBlockPolicies_success() {
            // given
            Long lineId = 100L;
            allowFamily(100L, lineId);
            when(repeatBlockMapper.selectRepeatBlocksByLineId(lineId))
                    .thenReturn(List.of(RepeatBlockPolicyResDto.builder().repeatBlockId(10L).lineId(lineId).build()));

            // when
            List<RepeatBlockPolicyResDto> result = userPolicyService.getRepeatBlockPolicies(lineId, userAuth(100L));

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRepeatBlockId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("즉시 차단 정책이 없으면 종료 시간을 null로 반환한다")
        void getImmediateBlockPolicy_whenMissing_returnsNullEndAt() {
            // given
            Long lineId = 100L;
            allowFamily(100L, lineId);
            when(immediateBlockMapper.selectImmediateBlockPolicy(lineId)).thenReturn(null);

            // when
            ImmediateBlockResDto result = userPolicyService.getImmediateBlockPolicy(lineId, userAuth(100L));

            // then
            assertThat(result.getLineId()).isEqualTo(lineId);
            assertThat(result.getBlockEndAt()).isNull();
        }

        @Test
        @DisplayName("라인 제한 정책이 없으면 빈 제한 DTO를 반환한다")
        void getLimitPolicy_whenMissing_returnsEmptyDto() {
            // given
            Long lineId = 100L;
            allowFamily(100L, lineId);
            when(lineLimitMapper.getExistLineLimitByLineId(lineId)).thenReturn(Optional.empty());

            // when
            LimitPolicyResDto result = userPolicyService.getLimitPolicy(lineId, userAuth(100L));

            // then
            assertThat(result.getLineLimitId()).isNull();
            assertThat(result.getDailyDataLimit()).isNull();
        }

        @Test
        @DisplayName("앱 정책 조회 시 기본 검색조건을 보정하고 페이징 결과를 반환한다")
        void getAppPolicies_success_appliesDefaultsAndPaging() {
            // given
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

            // when
            PagingResDto<AppPolicyResDto> result = userPolicyService.getAppPolicies(req, userAuth(100L));

            // then
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
        @DisplayName("적용된 정책 조회 시 반복/즉시 차단 정책을 함께 반환한다")
        void getAppliedPolicies_success() {
            // given
            Long lineId = 100L;
            allowFamily(100L, lineId);
            when(repeatBlockMapper.selectRepeatBlocksByLineId(lineId))
                    .thenReturn(List.of(RepeatBlockPolicyResDto.builder().repeatBlockId(1L).lineId(lineId).build()));
            when(immediateBlockMapper.selectImmediateBlockPolicy(lineId))
                    .thenReturn(ImmediateBlockResDto.builder().lineId(lineId).blockEndAt(LocalDateTime.now()).build());

            // when
            AppliedPolicyResDto result = userPolicyService.getAppliedPolicies(lineId, userAuth(100L));

            // then
            assertThat(result.getRepeatBlockPolicyList()).hasSize(1);
            assertThat(result.getImmediateBlock()).isNotNull();
        }

        @Test
        @DisplayName("앱 정책 조회 시 페이지 번호가 음수면 INVALID_PAGE_NUMBER를 던진다")
        void getAppPolicies_invalidPageNumber_throws() {
            // given
            AppPolicySearchCondReqDto req = AppPolicySearchCondReqDto.builder()
                    .lineId(100L)
                    .pageNumber(-1)
                    .pageSize(10)
                    .build();

            // when
            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.getAppPolicies(req, userAuth(100L)));

            // then
            assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_PAGE_NUMBER);
        }
    }

    @Nested
    @DisplayName("쓰기 메서드")
    class WriteMethods {

        @Test
        @DisplayName("반복 차단 정책 생성 성공 시 요일 정보 저장과 알림 발송을 수행한다")
        void createRepeatBlockPolicy_success() {
            // given
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

            // when
            RepeatBlockPolicyResDto result = userPolicyService.createRepeatBlockPolicy(req, userAuth(100L));

            // then
            assertThat(result.getRepeatBlockId()).isEqualTo(11L);
            verify(repeatBlockDayMapper).insertRepeatBlockDays(eq(11L), any());
            verify(alarmHistoryService).createAlarm(lineId, AlarmCode.POLICY_LIMIT, AlarmType.CREATE_REPEAT_BLOCK);
        }

        @Test
        @DisplayName("중복 시간대의 반복 차단 정책 생성 시 BLOCK_POLICY_CONFLICT를 던진다")
        void createRepeatBlockPolicy_whenConflict_throws() {
            // given
            Long lineId = 100L;
            allowFamily(100L, lineId);

            RepeatBlockDayReqDto day = new RepeatBlockDayReqDto();
            day.setDayOfWeek(DayOfWeek.MON);
            day.setStartAt(LocalTime.of(9, 0));
            day.setEndAt(LocalTime.of(18, 0));

            RepeatBlockPolicyReqDto req = new RepeatBlockPolicyReqDto();
            req.setDays(List.of(day));

            when(repeatBlockMapper.isDuplicatedRepeatBlocks(eq(lineId), any(), any(), any())).thenReturn(true);

            // when
            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.createRepeatBlockPolicy(req, userAuth(100L)));

            // then
            assertThat(ex.getErrorCode()).isEqualTo(PolicyErrorCode.BLOCK_POLICY_CONFLICT);
        }

        @Test
        @DisplayName("수정 대상 반복 차단 정책이 없으면 REPEAT_BLOCK_NOT_FOUND를 던진다")
        void updateRepeatBlockPolicy_whenNotFound_throws() {
            // given
            when(repeatBlockMapper.selectRepeatBlockById(1L)).thenReturn(null);

            // when
            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.updateRepeatBlockPolicy(1L, new RepeatBlockPolicyReqDto(), userAuth(100L)));

            // then
            assertThat(ex.getErrorCode()).isEqualTo(PolicyErrorCode.REPEAT_BLOCK_NOT_FOUND);
        }

        @Test
        @DisplayName("반복 차단 정책 수정 성공 시 기존 정책 비활성화 후 신규 정책을 생성한다")
        void updateRepeatBlockPolicy_success() {
            // given
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

            // when
            RepeatBlockPolicyResDto result = userPolicyService.updateRepeatBlockPolicy(1L, req, userAuth(100L));

            // then
            assertThat(result.getRepeatBlockId()).isEqualTo(22L);
            verify(repeatBlockMapper).deleteRepeatBlock(1L);
            verify(alarmHistoryService).createAlarm(lineId, AlarmCode.POLICY_CHANGE, AlarmType.UPDATE_REPEAT_BLOCK);
        }

        @Test
        @DisplayName("반복 차단 정책 삭제 성공 시 비활성 상태의 결과를 반환한다")
        void deleteRepeatBlockPolicy_success() {
            // given
            Long lineId = 100L;
            allowFamily(100L, lineId);
            when(repeatBlockMapper.selectRepeatBlockById(7L))
                    .thenReturn(RepeatBlockPolicyResDto.builder().repeatBlockId(7L).lineId(lineId).build());

            // when
            RepeatBlockPolicyResDto result = userPolicyService.deleteRepeatBlockPolicy(7L, userAuth(100L));

            // then
            assertThat(result.getRepeatBlockId()).isEqualTo(7L);
            assertThat(result.getIsActive()).isFalse();
        }

        @Test
        @DisplayName("즉시 차단 정책 수정 성공 시 종료시간 갱신과 알림 발송을 수행한다")
        void updateImmediateBlockPolicy_success() {
            // given
            Long lineId = 100L;
            allowFamily(100L, lineId);
            ImmediateBlockReqDto req = new ImmediateBlockReqDto();
            req.setBlockEndAt(LocalDateTime.of(2026, 3, 5, 10, 0));

            // when
            ImmediateBlockResDto result = userPolicyService.updateImmediateBlockPolicy(lineId, req, userAuth(100L));

            // then
            assertThat(result.getBlockEndAt()).isEqualTo(req.getBlockEndAt());
            verify(immediateBlockMapper).updateImmediateBlockPolicy(lineId, req);
            // 차단 정책 알림은 POLICY_LIMIT 채널을 사용한다.
            verify(alarmHistoryService).createAlarm(lineId, AlarmCode.POLICY_LIMIT, AlarmType.UPDATE_IMMEDIATE_BLOCK);
        }

        @Test
        @DisplayName("일일 총량 제한 토글 시 기존 정책을 활성화로 전환한다")
        void toggleDailyTotalLimitPolicy_existing_success() {
            // given
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
            when(lineLimitMapper.getExistLineLimitById(1L)).thenReturn(Optional.of(
                    LineLimit.builder()
                            .limitId(1L)
                            .lineId(lineId)
                            .dailyDataLimit(1000L)
                            .isDailyLimitActive(true)
                            .sharedDataLimit(5000L)
                            .isSharedLimitActive(true)
                            .build()
            ));
            when(policyWriteAuditServiceProvider.getIfAvailable()).thenReturn(policyWriteAuditService);

            // when
            LimitPolicyResDto result = userPolicyService.toggleDailyTotalLimitPolicy(lineId, userAuth(100L));

            // then
            assertThat(result.getIsDailyDataLimitActive()).isTrue();
            verify(alarmHistoryService).createAlarm(lineId, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_CREATE_DAYDATA_LIMIT);
            verify(policyWriteAuditService).saveLineLimitWriteAuditAfterCommit(
                    eq(PolicyWriteEventType.UPDATE),
                    eq("toggleDailyTotalLimitPolicy"),
                    any(LineLimit.class),
                    any(LineLimit.class),
                    eq(1L),
                    eq(100L)
            );
        }

        @Test
        @DisplayName("일일 총량 제한 토글 시 DB 업데이트 실패하면 DATABASE_ERROR를 던진다")
        void toggleDailyTotalLimitPolicy_dbFail_throws() {
            // given
            Long lineId = 100L;
            allowFamily(100L, lineId);
            LineLimit lineLimit = LineLimit.builder().limitId(1L).lineId(lineId).isDailyLimitActive(true).build();
            when(lineLimitMapper.getExistLineLimitByLineId(lineId)).thenReturn(Optional.of(lineLimit));
            when(lineLimitMapper.updateIsDailyLimitActiveById(1L, false)).thenReturn(0);

            // when
            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.toggleDailyTotalLimitPolicy(lineId, userAuth(100L)));

            // then
            assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.DATABASE_ERROR);
        }

        @Test
        @DisplayName("일일 총량 제한 정책이 없으면 신규 제한 정책 생성 CREATE 감사를 저장한다")
        void toggleDailyTotalLimitPolicy_whenMissing_insertsNew() {
            // given
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
            when(lineLimitMapper.getExistLineLimitById(99L)).thenReturn(Optional.of(
                    LineLimit.builder()
                            .limitId(99L)
                            .lineId(lineId)
                            .dailyDataLimit(-1L)
                            .isDailyLimitActive(true)
                            .sharedDataLimit(-1L)
                            .isSharedLimitActive(true)
                            .build()
            ));
            when(policyWriteAuditServiceProvider.getIfAvailable()).thenReturn(policyWriteAuditService);

            // when
            LimitPolicyResDto result = userPolicyService.toggleDailyTotalLimitPolicy(lineId, userAuth(100L));

            // then
            assertThat(result.getDailyDataLimit()).isEqualTo(-1L);
            assertThat(result.getIsSharedDataLimitActive()).isTrue();
            verify(policyWriteAuditService).saveLineLimitWriteAuditAfterCommit(
                    eq(PolicyWriteEventType.CREATE),
                    eq("insertNewLineLimit"),
                    eq(null),
                    any(LineLimit.class),
                    eq(1L),
                    eq(100L)
            );
        }

        @Test
        @DisplayName("일일 총량 제한 값 변경 성공 시 변경된 제한 값을 반환한다")
        void updateDailyTotalLimitPolicyValue_success() {
            // given
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
            when(policyWriteAuditServiceProvider.getIfAvailable()).thenReturn(policyWriteAuditService);

            // when
            LimitPolicyResDto result = userPolicyService.updateDailyTotalLimitPolicyValue(req, userAuth(100L));

            // then
            assertThat(result.getDailyDataLimit()).isEqualTo(777L);
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_CHANGE, AlarmType.POLICY_UPDATE_DAYDATA_LIMIT);
            verify(policyWriteAuditService).saveLineLimitWriteAuditAfterCommit(
                    eq(PolicyWriteEventType.UPDATE),
                    eq("updateDailyTotalLimitPolicyValue"),
                    any(LineLimit.class),
                    any(LineLimit.class),
                    eq(1L),
                    eq(100L)
            );
        }

        @Test
        @DisplayName("일일 총량 제한 값 변경 시 정책이 없으면 LIMIT_POLICY_NOT_FOUND를 던진다")
        void updateDailyTotalLimitPolicyValue_notFound_throws() {
            // given
            LimitPolicyUpdateReqDto req = new LimitPolicyUpdateReqDto();
            req.setLimitPolicyId(1L);
            when(lineLimitMapper.getExistLineLimitById(1L)).thenReturn(Optional.empty());

            // when
            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.updateDailyTotalLimitPolicyValue(req, userAuth(100L)));

            // then
            assertThat(ex.getErrorCode()).isEqualTo(PolicyErrorCode.LIMIT_POLICY_NOT_FOUND);
        }

        @Test
        @DisplayName("공유 풀 제한 토글 시 기존 정책을 활성화로 전환한다")
        void toggleSharedPoolLimitPolicy_existing_success() {
            // given
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
            when(lineLimitMapper.getExistLineLimitById(10L)).thenReturn(Optional.of(
                    LineLimit.builder()
                            .limitId(10L)
                            .lineId(lineId)
                            .dailyDataLimit(1000L)
                            .isDailyLimitActive(true)
                            .sharedDataLimit(3000L)
                            .isSharedLimitActive(true)
                            .build()
            ));
            when(policyWriteAuditServiceProvider.getIfAvailable()).thenReturn(policyWriteAuditService);

            // when
            LimitPolicyResDto result = userPolicyService.toggleSharedPoolLimitPolicy(lineId, userAuth(100L));

            // then
            assertThat(result.getIsSharedDataLimitActive()).isTrue();
            verify(alarmHistoryService).createAlarm(lineId, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_CREATE_SHAREDATA_LIMIT);
            verify(policyWriteAuditService).saveLineLimitWriteAuditAfterCommit(
                    eq(PolicyWriteEventType.UPDATE),
                    eq("toggleSharedPoolLimitPolicy"),
                    any(LineLimit.class),
                    any(LineLimit.class),
                    eq(1L),
                    eq(100L)
            );
        }

        @Test
        @DisplayName("공유 풀 제한 정책이 없으면 신규 생성 CREATE 감사를 저장한다")
        void toggleSharedPoolLimitPolicy_whenMissing_savesCreateAudit() {
            // given
            Long lineId = 100L;
            allowFamily(100L, lineId);
            when(lineLimitMapper.getExistLineLimitByLineId(lineId)).thenReturn(Optional.empty());
            doAnswer(inv -> {
                LineLimit ll = inv.getArgument(0);
                java.lang.reflect.Field field = LineLimit.class.getDeclaredField("limitId");
                field.setAccessible(true);
                field.set(ll, 88L);
                return 1;
            }).when(lineLimitMapper).createLineLimit(any());
            when(lineLimitMapper.getExistLineLimitById(88L)).thenReturn(Optional.of(
                    LineLimit.builder()
                            .limitId(88L)
                            .lineId(lineId)
                            .dailyDataLimit(-1L)
                            .isDailyLimitActive(true)
                            .sharedDataLimit(-1L)
                            .isSharedLimitActive(true)
                            .build()
            ));
            when(policyWriteAuditServiceProvider.getIfAvailable()).thenReturn(policyWriteAuditService);

            // when
            userPolicyService.toggleSharedPoolLimitPolicy(lineId, userAuth(100L));

            // then
            verify(policyWriteAuditService).saveLineLimitWriteAuditAfterCommit(
                    eq(PolicyWriteEventType.CREATE),
                    eq("insertNewLineLimit"),
                    eq(null),
                    any(LineLimit.class),
                    eq(1L),
                    eq(100L)
            );
        }

        @Test
        @DisplayName("공유 풀 제한 값 변경 성공 시 변경된 값을 반환한다")
        void updateSharedPoolLimitPolicyValue_success() {
            // given
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
            when(policyWriteAuditServiceProvider.getIfAvailable()).thenReturn(policyWriteAuditService);

            // when
            LimitPolicyResDto result = userPolicyService.updateSharedPoolLimitPolicyValue(req, userAuth(100L));

            // then
            assertThat(result.getSharedDataLimit()).isEqualTo(2222L);
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_CHANGE, AlarmType.POLICY_UPDATE_SHAREDATA_LIMIT);
            verify(policyWriteAuditService).saveLineLimitWriteAuditAfterCommit(
                    eq(PolicyWriteEventType.UPDATE),
                    eq("updateSharedPoolLimitPolicyValue"),
                    any(LineLimit.class),
                    any(LineLimit.class),
                    eq(1L),
                    eq(100L)
            );
        }

        @Test
        @DisplayName("앱 데이터 사용량 제한 변경 성공 시 변경된 제한값을 반환한다")
        void updateAppDataLimit_success() {
            // given
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
            when(appPolicyMapper.findEntityExistById(1L)).thenReturn(Optional.of(
                    AppPolicy.builder()
                            .appPolicyId(1L)
                            .lineId(100L)
                            .applicationId(10)
                            .dataLimit(100L)
                            .speedLimit(50)
                            .isActive(true)
                            .isWhitelist(false)
                            .build()
            ));
            allowFamily(100L, 100L);
            when(appPolicyMapper.updateDataLimit(1L, 5000L)).thenReturn(1);
            when(policyWriteAuditServiceProvider.getIfAvailable()).thenReturn(policyWriteAuditService);

            // when
            AppPolicyResDto result = userPolicyService.updateAppDataLimit(req, userAuth(100L));

            // then
            assertThat(result.getDailyLimitData()).isEqualTo(5000L);
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_CHANGE, AlarmType.POLICY_UPDATE_APP_USAGE_LIMIT);
            verify(policyWriteAuditService).saveAppPolicyWriteAuditAfterCommit(
                    eq(PolicyWriteEventType.UPDATE),
                    eq("updateAppDataLimit"),
                    any(AppPolicy.class),
                    any(AppPolicy.class),
                    eq(1L),
                    eq(100L)
            );
        }

        @Test
        @DisplayName("앱 데이터 사용량 제한 변경 시 DB 업데이트 실패하면 DATABASE_ERROR를 던진다")
        void updateAppDataLimit_dbFail_throws() {
            // given
            AppDataLimitUpdateReqDto req = new AppDataLimitUpdateReqDto();
            req.setAppPolicyId(1L);
            req.setValue(5000L);

            AppPolicyResDto appPolicy = AppPolicyResDto.builder().appPolicyId(1L).lineId(100L).build();
            when(appPolicyMapper.findEntityExistById(1L)).thenReturn(Optional.of(
                    AppPolicy.builder().appPolicyId(1L).lineId(100L).applicationId(10).build()
            ));
            when(appPolicyMapper.findDtoExistById(1L)).thenReturn(Optional.of(appPolicy));
            allowFamily(100L, 100L);
            when(appPolicyMapper.updateDataLimit(1L, 5000L)).thenReturn(0);

            // when
            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.updateAppDataLimit(req, userAuth(100L)));

            // then
            assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.DATABASE_ERROR);
        }

        @Test
        @DisplayName("앱 속도 제한 변경 성공 시 변경된 속도 제한값을 반환한다")
        void updateAppSpeedLimit_success() {
            // given
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
            when(appPolicyMapper.findEntityExistById(2L)).thenReturn(Optional.of(
                    AppPolicy.builder()
                            .appPolicyId(2L)
                            .lineId(100L)
                            .applicationId(20)
                            .dataLimit(100L)
                            .speedLimit(50)
                            .isActive(true)
                            .isWhitelist(false)
                            .build()
            ));
            allowFamily(100L, 100L);
            when(appPolicyMapper.updateSpeedLimit(2L, 70)).thenReturn(1);
            when(policyWriteAuditServiceProvider.getIfAvailable()).thenReturn(policyWriteAuditService);

            // when
            AppPolicyResDto result = userPolicyService.updateAppSpeedLimit(req, userAuth(100L));

            // then
            assertThat(result.getDailyLimitSpeed()).isEqualTo(70);
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_CHANGE, AlarmType.POLICY_UPDATE_DATA_SPEED_LIMIT);
            verify(policyWriteAuditService).saveAppPolicyWriteAuditAfterCommit(
                    eq(PolicyWriteEventType.UPDATE),
                    eq("updateAppSpeedLimit"),
                    any(AppPolicy.class),
                    any(AppPolicy.class),
                    eq(1L),
                    eq(100L)
            );
        }

        @Test
        @DisplayName("기존 앱 정책의 활성화 토글 시 활성 상태로 변경하고 알림을 발송한다")
        void toggleAppPolicyActive_existingPolicy_success() {
            // given
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
            when(appPolicyMapper.findEntityExistById(1L)).thenReturn(Optional.of(
                    AppPolicy.builder().appPolicyId(1L).lineId(100L).applicationId(10).isActive(false).build()
            ));
            when(policyWriteAuditServiceProvider.getIfAvailable()).thenReturn(policyWriteAuditService);

            // when
            AppPolicyResDto result = userPolicyService.toggleAppPolicyActive(req, userAuth(100L));

            // then
            assertThat(result.getIsActive()).isTrue();
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_CREATE_APP_USAGE_LIMIT);
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_CREATE_DATA_SPEED_LIMIT);
            verify(policyWriteAuditService).saveAppPolicyWriteAuditAfterCommit(
                    eq(PolicyWriteEventType.UPDATE),
                    eq("toggleAppPolicyActive"),
                    any(AppPolicy.class),
                    any(AppPolicy.class),
                    eq(1L),
                    eq(100L)
            );
        }

        @Test
        @DisplayName("앱 정책이 없으면 신규 앱 정책 생성 CREATE 감사를 저장한다")
        void toggleAppPolicyActive_newInsert_success() {
            // given
            AppPolicyActiveToggleReqDto req = new AppPolicyActiveToggleReqDto();
            req.setLineId(100L);
            req.setApplicationId(10);
            allowFamily(100L, 100L);

            AppPolicyResDto appBase = AppPolicyResDto.builder()
                    .appPolicyId(null)
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
            when(appPolicyMapper.findEntityExistById(55L)).thenReturn(Optional.of(
                    AppPolicy.builder()
                            .appPolicyId(55L)
                            .lineId(100L)
                            .applicationId(10)
                            .dataLimit(-1L)
                            .speedLimit(-1)
                            .isActive(true)
                            .build()
            ));
            when(policyWriteAuditServiceProvider.getIfAvailable()).thenReturn(policyWriteAuditService);

            // when
            AppPolicyResDto result = userPolicyService.toggleAppPolicyActive(req, userAuth(100L));

            // then
            assertThat(result.getAppPolicyId()).isEqualTo(55L);
            assertThat(result.getIsActive()).isTrue();
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_CREATE_APP_USAGE_LIMIT);
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_CREATE_DATA_SPEED_LIMIT);
            verify(policyWriteAuditService).saveAppPolicyWriteAuditAfterCommit(
                    eq(PolicyWriteEventType.CREATE),
                    eq("toggleAppPolicyActive"),
                    eq(null),
                    any(AppPolicy.class),
                    eq(1L),
                    eq(100L)
            );
        }

        @Test
        @DisplayName("앱 정책 활성화 토글 시 앱 정보가 없으면 APP_NOT_FOUND를 던진다")
        void toggleAppPolicyActive_whenAppMissing_throws() {
            // given
            AppPolicyActiveToggleReqDto req = new AppPolicyActiveToggleReqDto();
            req.setLineId(100L);
            req.setApplicationId(10);
            allowFamily(100L, 100L);
            when(appPolicyMapper.findDtoExistByLineIdAndAppId(100L, 10)).thenReturn(Optional.empty());

            // when
            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.toggleAppPolicyActive(req, userAuth(100L)));

            // then
            assertThat(ex.getErrorCode()).isEqualTo(PolicyErrorCode.APP_NOT_FOUND);
        }

        @Test
        @DisplayName("화이트리스트 토글 성공 시 화이트리스트 상태를 true로 변경한다")
        void toggleAppPolicyWhitelist_success() {
            // given
            AppPolicyResDto appPolicy = AppPolicyResDto.builder()
                    .appPolicyId(1L)
                    .lineId(100L)
                    .isWhiteList(false)
                    .build();
            when(appPolicyMapper.findDtoExistById(1L)).thenReturn(Optional.of(appPolicy));
            when(appPolicyMapper.findEntityExistById(1L)).thenReturn(Optional.of(
                    AppPolicy.builder().appPolicyId(1L).lineId(100L).applicationId(10).isWhitelist(false).build()
            ));
            allowFamily(100L, 100L);
            when(appPolicyMapper.updateIsWhitelist(1L, true)).thenReturn(1);
            when(policyWriteAuditServiceProvider.getIfAvailable()).thenReturn(policyWriteAuditService);

            // when
            AppPolicyResDto result = userPolicyService.toggleAppPolicyWhitelist(1L, userAuth(100L));

            // then
            assertThat(result.getIsWhiteList()).isTrue();
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_ADD_WHITELIST);
            verify(policyWriteAuditService).saveAppPolicyWriteAuditAfterCommit(
                    eq(PolicyWriteEventType.UPDATE),
                    eq("toggleAppPolicyWhitelist"),
                    any(AppPolicy.class),
                    any(AppPolicy.class),
                    eq(1L),
                    eq(100L)
            );
        }

        @Test
        @DisplayName("앱 정책 삭제 성공 시 삭제 처리와 관련 알림 발송을 수행한다")
        void deleteAppPolicy_success() {
            // given
            AppPolicy appPolicy = AppPolicy.builder().appPolicyId(1L).lineId(100L).applicationId(10).build();
            when(appPolicyMapper.findEntityExistById(1L)).thenReturn(Optional.of(appPolicy));
            allowFamily(100L, 100L);
            when(appPolicyMapper.setDeleted(1L)).thenReturn(1);
            when(appPolicyMapper.findEntityById(1L)).thenReturn(Optional.of(
                    AppPolicy.builder()
                            .appPolicyId(1L)
                            .lineId(100L)
                            .applicationId(10)
                            .deletedAt(LocalDateTime.of(2026, 3, 13, 10, 0))
                            .build()
            ));
            when(policyWriteAuditServiceProvider.getIfAvailable()).thenReturn(policyWriteAuditService);

            // when
            userPolicyService.deleteAppPolicy(1L, userAuth(100L));

            // then
            verify(appPolicyMapper).setDeleted(1L);
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_DELETE_APP_USAGE_LIMIT);
            verify(alarmHistoryService).createAlarm(100L, AlarmCode.POLICY_LIMIT, AlarmType.POLICY_DELETE_DATA_SPEED_LIMIT);
            verify(policyWriteAuditService).saveAppPolicyWriteAuditAfterCommit(
                    eq(PolicyWriteEventType.DELETE),
                    eq("deleteAppPolicy"),
                    any(AppPolicy.class),
                    any(AppPolicy.class),
                    eq(1L),
                    eq(100L)
            );
        }

        @Test
        @DisplayName("삭제 대상 앱 정책이 없으면 APP_POLICY_NOT_FOUND를 던진다")
        void deleteAppPolicy_notFound_throws() {
            // given
            when(appPolicyMapper.findEntityExistById(1L)).thenReturn(Optional.empty());

            // when
            ApplicationException ex = assertThrows(ApplicationException.class,
                    () -> userPolicyService.deleteAppPolicy(1L, userAuth(100L)));

            // then
            assertThat(ex.getErrorCode()).isEqualTo(PolicyErrorCode.APP_POLICY_NOT_FOUND);
        }
    }
}
