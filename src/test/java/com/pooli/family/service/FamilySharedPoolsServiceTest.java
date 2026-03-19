package com.pooli.family.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.family.domain.dto.mongo.SharedPoolTransferLog;
import com.pooli.family.domain.dto.request.UpdateSharedDataThresholdReqDto;
import com.pooli.family.domain.dto.response.FamilySharedPoolResDto;
import com.pooli.family.domain.dto.response.SharedDataThresholdResDto;
import com.pooli.family.domain.dto.response.SharedPoolDetailResDto;
import com.pooli.family.domain.dto.response.SharedPoolHistoryItemResDto;
import com.pooli.family.domain.dto.response.SharedPoolMainResDto;
import com.pooli.family.domain.dto.response.SharedPoolMonthlyUsageResDto;
import com.pooli.family.domain.dto.response.SharedPoolMyStatusResDto;
import com.pooli.family.domain.entity.SharedPoolDomain;
import com.pooli.family.exception.SharedPoolErrorCode;
import com.pooli.family.mapper.FamilyMapper;
import com.pooli.family.mapper.FamilySharedPoolMapper;
import com.pooli.family.repository.mongo.SharedPoolTransferLogRepository;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.service.AlarmHistoryService;
import com.pooli.traffic.service.runtime.TrafficBalanceStateWriteThroughService;
import com.pooli.traffic.service.runtime.TrafficQuotaCacheService;
import com.pooli.traffic.service.runtime.TrafficRemainingBalanceQueryService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

@ExtendWith(MockitoExtension.class)
class FamilySharedPoolsServiceTest {

    @Mock
    private FamilySharedPoolMapper sharedPoolMapper;

    @Mock
    private FamilyMapper familyMapper;

    @Mock
    private AlarmHistoryService alarmHistoryService;

    @Mock
    private SharedPoolTransferLogRepository transferLogRepository;

    @Mock
    private ObjectProvider<TrafficBalanceStateWriteThroughService> trafficBalanceStateWriteThroughServiceProvider;

    @Mock
    private TrafficBalanceStateWriteThroughService trafficBalanceStateWriteThroughService;

    @Mock
    private TrafficRemainingBalanceQueryService trafficRemainingBalanceQueryService;

    @Mock
    private ObjectProvider<TrafficRedisKeyFactory> trafficRedisKeyFactoryProvider;

    @Mock
    private ObjectProvider<TrafficRedisRuntimePolicy> trafficRedisRuntimePolicyProvider;

    @Mock
    private ObjectProvider<TrafficQuotaCacheService> trafficQuotaCacheServiceProvider;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Mock
    private TrafficQuotaCacheService trafficQuotaCacheService;

    private FamilySharedPoolsService service;

    @BeforeEach
    void setUp() {
        service = new FamilySharedPoolsService(
                sharedPoolMapper,
                familyMapper,
                alarmHistoryService,
                transferLogRepository,
                trafficBalanceStateWriteThroughServiceProvider,
                trafficRemainingBalanceQueryService,
                trafficRedisKeyFactoryProvider,
                trafficRedisRuntimePolicyProvider,
                trafficQuotaCacheServiceProvider
        );
    }

    @Test
    @DisplayName("getFamilyIdByLineId returns family id")
    void getFamilyIdByLineId_success() {
        when(sharedPoolMapper.selectFamilyIdByLineId(101L)).thenReturn(1L);

        Long result = service.getFamilyIdByLineId(101L);

        assertThat(result).isEqualTo(1L);
        verify(sharedPoolMapper).selectFamilyIdByLineId(101L);
    }

    @Test
    @DisplayName("getFamilyIdByLineId throws when line is not in family")
    void getFamilyIdByLineId_notFamilyMember() {
        when(sharedPoolMapper.selectFamilyIdByLineId(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getFamilyIdByLineId(999L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.NOT_FAMILY_MEMBER));
    }

    @Test
    @DisplayName("getMySharedPoolStatus returns actual member remaining")
    void getMySharedPoolStatus_success() {
        SharedPoolDomain domain = SharedPoolDomain.builder()
                .remainingData(8_000_000L)
                .personalContribution(500_000L)
                .build();

        when(sharedPoolMapper.selectMySharedPoolStatus(101L)).thenReturn(domain);
        when(trafficRemainingBalanceQueryService.resolveIndividualActualRemaining(101L, 8_000_000L))
                .thenReturn(8_500_000L);

        SharedPoolMyStatusResDto result = service.getMySharedPoolStatus(101L);

        assertThat(result.getRemainingData()).isEqualTo(8_500_000L);
        assertThat(result.getContributionAmount()).isEqualTo(500_000L);
    }

    @Test
    @DisplayName("getMySharedPoolStatus throws when shared pool is missing")
    void getMySharedPoolStatus_notFound() {
        when(sharedPoolMapper.selectMySharedPoolStatus(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getMySharedPoolStatus(999L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND));
    }

    @Test
    @DisplayName("getFamilySharedPool returns actual family remaining")
    void getFamilySharedPool_success() {
        SharedPoolDomain domain = SharedPoolDomain.builder()
                .poolTotalData(1_000_000L)
                .poolRemainingData(800_000L)
                .poolBaseData(0L)
                .monthlyUsageAmount(0L)
                .monthlyContributionAmount(0L)
                .build();

        when(sharedPoolMapper.selectFamilySharedPool(1L)).thenReturn(domain);
        when(trafficRemainingBalanceQueryService.resolveSharedActualRemaining(1L, 800_000L)).thenReturn(950_000L);

        FamilySharedPoolResDto result = service.getFamilySharedPool(1L);

        assertThat(result.getPoolTotalData()).isEqualTo(1_000_000L);
        assertThat(result.getPoolRemainingData()).isEqualTo(950_000L);
        assertThat(result.getPoolBaseData()).isEqualTo(0L);
        assertThat(result.getMonthlyUsageAmount()).isEqualTo(0L);
        assertThat(result.getMonthlyContributionAmount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("getFamilySharedPool throws when family pool is missing")
    void getFamilySharedPool_notFound() {
        when(sharedPoolMapper.selectFamilySharedPool(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getFamilySharedPool(999L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND));
    }

    @Test
    @DisplayName("contributeToSharedPool updates pool and stores Mongo log")
    void contributeToSharedPool_success() {
        when(sharedPoolMapper.selectRemainingData(101L)).thenReturn(8_000_000L);
        when(sharedPoolMapper.selectMonthlyContributionByFamilyId(eq(101L), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(0L);
        when(sharedPoolMapper.selectLineIdsByFamilyId(1L)).thenReturn(List.of(101L, 201L));
        when(trafficBalanceStateWriteThroughServiceProvider.getIfAvailable())
                .thenReturn(trafficBalanceStateWriteThroughService);

        service.contributeToSharedPool(101L, 1L, 500_000L);

        verify(sharedPoolMapper).updateLineRemainingData(101L, 500_000L);
        verify(sharedPoolMapper).updateFamilyPoolData(1L, 500_000L);
        verify(sharedPoolMapper).insertContribution(1L, 101L, 500_000L);
        verify(trafficBalanceStateWriteThroughService).markSharedBalanceNotEmpty(1L);
        verify(trafficBalanceStateWriteThroughService).markSharedMetaContribution(1L, 500_000L);
        verify(transferLogRepository).save(any(SharedPoolTransferLog.class));
        verify(alarmHistoryService).createAlarm(eq(201L), eq(AlarmCode.FAMILY), eq(AlarmType.SHARED_POOL_CONTRIBUTION));
        verify(alarmHistoryService, never()).createAlarm(eq(101L), any(), any());
    }

    @Test
    @DisplayName("contributeToSharedPool throws when line is missing")
    void contributeToSharedPool_lineNotFound() {
        when(sharedPoolMapper.selectRemainingData(999L)).thenReturn(null);
        when(sharedPoolMapper.selectMonthlyContributionByFamilyId(eq(999L), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(0L);

        assertThatThrownBy(() -> service.contributeToSharedPool(999L, 1L, 500_000L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.LINE_NOT_FOUND));

        verify(sharedPoolMapper, never()).updateLineRemainingData(anyLong(), anyLong());
        verify(transferLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("contributeToSharedPool throws when member data is insufficient")
    void contributeToSharedPool_insufficientData() {
        when(sharedPoolMapper.selectRemainingData(101L)).thenReturn(100L);
        when(sharedPoolMapper.selectMonthlyContributionByFamilyId(eq(101L), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(0L);

        assertThatThrownBy(() -> service.contributeToSharedPool(101L, 1L, 500_000L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.INSUFFICIENT_DATA));

        verify(sharedPoolMapper, never()).updateLineRemainingData(anyLong(), anyLong());
        verify(transferLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("getSharedPoolDetail returns actual remaining without line limit")
    void getSharedPoolDetail_withoutLimit() {
        SharedPoolDomain domain = SharedPoolDomain.builder()
                .basicDataAmount(10_000_000L)
                .remainingData(8_000_000L)
                .poolTotalData(1_000_000L)
                .poolRemainingData(800_000L)
                .build();

        when(sharedPoolMapper.selectSharedPoolDetail(1L, 101L)).thenReturn(domain);
        when(sharedPoolMapper.selectSharedDataLimit(101L)).thenReturn(null);
        when(trafficRemainingBalanceQueryService.resolveIndividualActualRemaining(101L, 8_000_000L))
                .thenReturn(8_300_000L);
        when(trafficRemainingBalanceQueryService.resolveSharedActualRemaining(1L, 800_000L))
                .thenReturn(900_000L);

        SharedPoolDetailResDto result = service.getSharedPoolDetail(1L, 101L);

        assertThat(result.getRemainingDataAmount()).isEqualTo(8_300_000L);
        assertThat(result.getSharedPoolTotalAmount()).isEqualTo(1_000_000L);
        assertThat(result.getSharedPoolRemainingAmount()).isEqualTo(900_000L);
    }

    @Test
    @DisplayName("getSharedPoolDetail applies line limit with min comparison")
    void getSharedPoolDetail_withLimit() {
        SharedPoolDomain domain = SharedPoolDomain.builder()
                .basicDataAmount(10_000_000L)
                .remainingData(8_000_000L)
                .poolTotalData(1_000_000L)
                .poolRemainingData(800_000L)
                .build();

        when(sharedPoolMapper.selectSharedPoolDetail(1L, 101L)).thenReturn(domain);
        when(sharedPoolMapper.selectSharedDataLimit(101L)).thenReturn(500_000L);
        when(trafficRemainingBalanceQueryService.resolveIndividualActualRemaining(101L, 8_000_000L))
                .thenReturn(8_200_000L);
        when(trafficRemainingBalanceQueryService.resolveSharedActualRemaining(1L, 800_000L))
                .thenReturn(650_000L);

        SharedPoolDetailResDto result = service.getSharedPoolDetail(1L, 101L);

        assertThat(result.getRemainingDataAmount()).isEqualTo(8_200_000L);
        assertThat(result.getSharedPoolTotalAmount()).isEqualTo(500_000L);
        assertThat(result.getSharedPoolRemainingAmount()).isEqualTo(500_000L);
    }

    @Test
    @DisplayName("getSharedPoolDetail treats unlimited shared limit as uncapped")
    void getSharedPoolDetail_withUnlimitedLimit() {
        SharedPoolDomain domain = SharedPoolDomain.builder()
                .basicDataAmount(10_000_000L)
                .remainingData(8_000_000L)
                .poolTotalData(1_000_000L)
                .poolRemainingData(800_000L)
                .build();

        when(sharedPoolMapper.selectSharedPoolDetail(1L, 101L)).thenReturn(domain);
        when(sharedPoolMapper.selectSharedDataLimit(101L)).thenReturn(-1L);
        when(trafficRemainingBalanceQueryService.resolveIndividualActualRemaining(101L, 8_000_000L))
                .thenReturn(8_200_000L);
        when(trafficRemainingBalanceQueryService.resolveSharedActualRemaining(1L, 800_000L))
                .thenReturn(650_000L);

        SharedPoolDetailResDto result = service.getSharedPoolDetail(1L, 101L);

        assertThat(result.getSharedPoolTotalAmount()).isEqualTo(1_000_000L);
        assertThat(result.getSharedPoolRemainingAmount()).isEqualTo(650_000L);
    }

    @Test
    @DisplayName("getSharedPoolDetail throws when shared pool detail is missing")
    void getSharedPoolDetail_notFound() {
        when(sharedPoolMapper.selectSharedPoolDetail(999L, 999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getSharedPoolDetail(999L, 999L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND));
    }

    @Test
    @DisplayName("getSharedPoolMain returns dashboard summary with actual remaining")
    void getSharedPoolMain_success() {
        SharedPoolDomain domain = SharedPoolDomain.builder()
                .poolBaseData(0L)
                .monthlyContributionAmount(500_000L)
                .poolTotalData(1_500_000L)
                .poolRemainingData(1_200_000L)
                .build();

        when(sharedPoolMapper.selectSharedPoolMain(1L)).thenReturn(domain);
        when(trafficRemainingBalanceQueryService.resolveSharedActualRemaining(1L, 1_200_000L))
                .thenReturn(1_350_000L);

        SharedPoolMainResDto result = service.getSharedPoolMain(1L);

        assertThat(result.getSharedPoolBaseData()).isEqualTo(0L);
        assertThat(result.getSharedPoolAdditionalData()).isEqualTo(500_000L);
        assertThat(result.getSharedPoolTotalData()).isEqualTo(1_500_000L);
        assertThat(result.getSharedPoolRemainingData()).isEqualTo(1_350_000L);
    }

    @Test
    @DisplayName("getSharedPoolMain throws when dashboard data is missing")
    void getSharedPoolMain_notFound() {
        when(sharedPoolMapper.selectSharedPoolMain(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getSharedPoolMain(999L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND));
    }

    @Test
    @DisplayName("getFamilyMonthlySharedUsageTotal returns max usage between DB and Redis")
    void getFamilyMonthlySharedUsageTotal_usesMaxUsage() {
        AuthUserDetails principal = AuthUserDetails.builder()
                .lineId(101L)
                .build();
        ZoneId zoneId = ZoneId.of("Asia/Seoul");
        YearMonth targetMonth = YearMonth.now(zoneId);

        when(sharedPoolMapper.selectFamilyIdByLineId(101L)).thenReturn(1L);
        when(sharedPoolMapper.selectFamilyPoolTotalData(1L)).thenReturn(10_000L);
        when(sharedPoolMapper.selectFamilyMonthlySharedUsageByLine(1L)).thenReturn(List.of(
                SharedPoolMonthlyUsageResDto.MemberUsageDto.builder()
                        .userName("김민준")
                        .phoneNumber("01010000000")
                        .monthlySharedPoolUsage(2_000L)
                        .lineId(101L)
                        .build(),
                SharedPoolMonthlyUsageResDto.MemberUsageDto.builder()
                        .userName("박서준")
                        .phoneNumber("01020000000")
                        .monthlySharedPoolUsage(3_000L)
                        .lineId(201L)
                        .build()
        ));
        when(trafficRedisKeyFactoryProvider.getIfAvailable()).thenReturn(trafficRedisKeyFactory);
        when(trafficRedisRuntimePolicyProvider.getIfAvailable()).thenReturn(trafficRedisRuntimePolicy);
        when(trafficQuotaCacheServiceProvider.getIfAvailable()).thenReturn(trafficQuotaCacheService);
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(zoneId);
        when(trafficRedisKeyFactory.monthlySharedUsageKey(101L, targetMonth)).thenReturn("monthly:101");
        when(trafficRedisKeyFactory.monthlySharedUsageKey(201L, targetMonth)).thenReturn("monthly:201");
        when(trafficQuotaCacheService.readValueOrDefault("monthly:101", 0L)).thenReturn(5_000L);
        when(trafficQuotaCacheService.readValueOrDefault("monthly:201", 0L)).thenReturn(1_000L);

        SharedPoolMonthlyUsageResDto result = service.getFamilyMonthlySharedUsageTotal(principal);

        assertThat(result.getSharedPoolTotalData()).isEqualTo(10_000L);
        assertThat(result.getMembersUsageList()).hasSize(2);
        assertThat(result.getMembersUsageList().get(0).getMonthlySharedPoolUsage()).isEqualTo(5_000L);
        assertThat(result.getMembersUsageList().get(1).getMonthlySharedPoolUsage()).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("getFamilyMonthlySharedUsageTotal keeps DB usage when Redis query is unavailable")
    void getFamilyMonthlySharedUsageTotal_withoutRedisFallsBackToDb() {
        AuthUserDetails principal = AuthUserDetails.builder()
                .lineId(101L)
                .build();

        when(sharedPoolMapper.selectFamilyIdByLineId(101L)).thenReturn(1L);
        when(sharedPoolMapper.selectFamilyPoolTotalData(1L)).thenReturn(10_000L);
        when(sharedPoolMapper.selectFamilyMonthlySharedUsageByLine(1L)).thenReturn(List.of(
                SharedPoolMonthlyUsageResDto.MemberUsageDto.builder()
                        .userName("김민준")
                        .phoneNumber("01010000000")
                        .monthlySharedPoolUsage(2_000L)
                        .lineId(101L)
                        .build()
        ));
        when(trafficRedisKeyFactoryProvider.getIfAvailable()).thenReturn(null);

        SharedPoolMonthlyUsageResDto result = service.getFamilyMonthlySharedUsageTotal(principal);

        assertThat(result.getMembersUsageList()).hasSize(1);
        assertThat(result.getMembersUsageList().get(0).getMonthlySharedPoolUsage()).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("getSharedDataThreshold uses actual remaining to derive min threshold")
    void getSharedDataThreshold_success() {
        SharedPoolDomain domain = SharedPoolDomain.builder()
                .isThresholdActive(true)
                .familyThreshold(200_000L)
                .poolTotalData(500_000L)
                .poolRemainingData(300_000L)
                .build();

        when(sharedPoolMapper.selectSharedDataThreshold(1L)).thenReturn(domain);
        when(trafficRemainingBalanceQueryService.resolveSharedActualRemaining(1L, 300_000L))
                .thenReturn(350_000L);

        SharedDataThresholdResDto result = service.getSharedDataThreshold(1L);

        assertThat(result.getIsThresholdActive()).isTrue();
        assertThat(result.getFamilyThreshold()).isEqualTo(200_000L);
        assertThat(result.getMinThreshold()).isEqualTo(150_000L);
        assertThat(result.getMaxThreshold()).isEqualTo(500_000L);
    }

    @Test
    @DisplayName("getSharedDataThreshold throws when threshold data is missing")
    void getSharedDataThreshold_notFound() {
        when(sharedPoolMapper.selectSharedDataThreshold(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getSharedDataThreshold(999L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND));
    }

    @Test
    @DisplayName("updateSharedDataThreshold updates threshold and sends family alarms")
    void updateSharedDataThreshold_success() {
        UpdateSharedDataThresholdReqDto request = new UpdateSharedDataThresholdReqDto();
        request.setNewFamilyThreshold(100_000L);
        when(sharedPoolMapper.selectLineIdsByFamilyId(1L)).thenReturn(List.of(101L, 201L));

        service.updateSharedDataThreshold(1L, request);

        verify(sharedPoolMapper).updateSharedDataThreshold(1L, 100_000L);
        verify(alarmHistoryService).createAlarm(eq(101L), eq(AlarmCode.FAMILY), eq(AlarmType.SHARED_POOL_THRESHOLD_CHANGE));
        verify(alarmHistoryService).createAlarm(eq(201L), eq(AlarmCode.FAMILY), eq(AlarmType.SHARED_POOL_THRESHOLD_CHANGE));
    }

    @Test
    @DisplayName("getSharedPoolHistory merges contribution and usage history")
    void getSharedPoolHistory_success() {
        AuthUserDetails principal = AuthUserDetails.builder()
                .lineId(101L)
                .build();

        SharedPoolHistoryItemResDto contributionItem = SharedPoolHistoryItemResDto.builder()
                .eventType("CONTRIBUTION")
                .title("데이터 보태기")
                .userName("kim")
                .occurredAt("2026-03-15")
                .amount(5_000_000_000L)
                .precision("DAY")
                .build();

        SharedPoolHistoryItemResDto usageItem = SharedPoolHistoryItemResDto.builder()
                .eventType("USAGE")
                .title("데이터 사용")
                .userName("park")
                .occurredAt("2026-03-15")
                .amount(1_200_000_000L)
                .precision("DAY")
                .build();

        when(sharedPoolMapper.selectFamilyIdByLineId(101L)).thenReturn(1L);
        when(sharedPoolMapper.selectSharedPoolUsageHistory(
                eq(1L),
                eq(LocalDate.of(2026, 3, 1)),
                eq(LocalDate.of(2026, 4, 1))
        )).thenReturn(List.of(usageItem));
        when(sharedPoolMapper.selectSharedPoolContributionHistory(
                eq(1L),
                eq(LocalDate.of(2026, 3, 1)),
                eq(LocalDate.of(2026, 4, 1))
        )).thenReturn(List.of(contributionItem));

        List<SharedPoolHistoryItemResDto> result = service.getSharedPoolHistory(principal, 202603);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getEventType()).isEqualTo("CONTRIBUTION");
        assertThat(result.get(0).getTitle()).isEqualTo("데이터 보태기");
        assertThat(result.get(0).getUserName()).isEqualTo("kim");
        assertThat(result.get(0).getPrecision()).isEqualTo("DAY");
        assertThat(result.get(1).getEventType()).isEqualTo("USAGE");
        assertThat(result.get(1).getPrecision()).isEqualTo("DAY");
    }

    @Test
    @DisplayName("getSharedPoolHistory throws for invalid yearMonth")
    void getSharedPoolHistory_invalidYearMonth() {
        AuthUserDetails principal = AuthUserDetails.builder()
                .lineId(101L)
                .build();

        assertThatThrownBy(() -> service.getSharedPoolHistory(principal, 20261))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.INVALID_REQUEST_PARAM));
    }

    @Nested
    @DisplayName("alarm dispatch")
    class AlarmTest {

        @Test
        @DisplayName("contribution alarm skips when there are no other members")
        void contributeAlarm_noOtherMembers() {
            when(sharedPoolMapper.selectRemainingData(101L)).thenReturn(8_000_000L);
            when(sharedPoolMapper.selectMonthlyContributionByFamilyId(eq(101L), any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(0L);
            when(sharedPoolMapper.selectLineIdsByFamilyId(1L)).thenReturn(List.of(101L));

            service.contributeToSharedPool(101L, 1L, 500_000L);

            verify(alarmHistoryService, never()).createAlarm(anyLong(), any(), any());
        }

        @Test
        @DisplayName("contribution alarm notifies every member except actor")
        void contributeAlarm_threeMembers() {
            when(sharedPoolMapper.selectRemainingData(101L)).thenReturn(8_000_000L);
            when(sharedPoolMapper.selectMonthlyContributionByFamilyId(eq(101L), any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(0L);
            when(sharedPoolMapper.selectLineIdsByFamilyId(1L)).thenReturn(List.of(101L, 201L, 301L));

            service.contributeToSharedPool(101L, 1L, 500_000L);

            verify(alarmHistoryService, times(2))
                    .createAlarm(anyLong(), eq(AlarmCode.FAMILY), eq(AlarmType.SHARED_POOL_CONTRIBUTION));
            verify(alarmHistoryService, never()).createAlarm(eq(101L), any(), any());
        }

        @Test
        @DisplayName("threshold alarm is skipped when family has no members")
        void thresholdAlarm_noMembers() {
            UpdateSharedDataThresholdReqDto request = new UpdateSharedDataThresholdReqDto();
            request.setNewFamilyThreshold(50_000L);
            when(sharedPoolMapper.selectLineIdsByFamilyId(1L)).thenReturn(Collections.emptyList());

            service.updateSharedDataThreshold(1L, request);

            verify(alarmHistoryService, never()).createAlarm(anyLong(), any(), any());
        }
    }
}
