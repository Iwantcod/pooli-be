package com.pooli.data.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.data.domain.dto.response.AppDataUsageResDto;
import com.pooli.data.domain.dto.response.DataBalancesResDto;
import com.pooli.data.domain.dto.response.DataUsageResDto;
import com.pooli.data.domain.dto.response.MonthlyDataUsageResDto;
import com.pooli.data.domain.dto.response.MonthlyDataUsageResDto.MonthlyUsageDto;
import com.pooli.data.error.DataErrorCode;
import com.pooli.data.mapper.DataMapper;
import com.pooli.family.domain.dto.response.FamilyMembersResDto;
import com.pooli.family.domain.entity.FamilyLine;
import com.pooli.family.service.FamilySharedPoolsService;
import com.pooli.permission.mapper.FamilyLineMapper;
import com.pooli.permission.mapper.PermissionLineMapper;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

@ExtendWith(MockitoExtension.class)
class DataServiceImplTest {

    @Mock
    private DataMapper dataMapper;

    @Mock
    private PermissionLineMapper permissionLineMapper;

    @Mock
    private FamilyLineMapper familyLineMapper;

    @Mock
    private FamilySharedPoolsService familySharedPoolsService;

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @InjectMocks
    private DataServiceImpl dataService;

    @Test
    @DisplayName("월별 데이터 사용량: yearMonth가 null이면 INVALID_MONTH")
    void getMonthlyDataUsage_nullMonth_throws() {
        assertThatThrownBy(() -> dataService.getMonthlyDataUsage(1L, null))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(DataErrorCode.INVALID_MONTH));
    }

    @Test
    @DisplayName("월별 데이터 사용량: yearMonth 하한 미만이면 INVALID_MONTH")
    void getMonthlyDataUsage_tooSmallMonth_throws() {
        assertThatThrownBy(() -> dataService.getMonthlyDataUsage(1L, 100000))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(DataErrorCode.INVALID_MONTH));
    }

    @Test
    @DisplayName("월별 데이터 사용량: yearMonth 상한 초과면 INVALID_MONTH")
    void getMonthlyDataUsage_tooLargeMonth_throws() {
        assertThatThrownBy(() -> dataService.getMonthlyDataUsage(1L, 1000000))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(DataErrorCode.INVALID_MONTH));
    }

    @Test
    @DisplayName("월별 데이터 사용량: month 형식이 잘못되면 INVALID_MONTH")
    void getMonthlyDataUsage_invalidMonth_throws() {
        assertThatThrownBy(() -> dataService.getMonthlyDataUsage(1L, 202313))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(DataErrorCode.INVALID_MONTH));
    }

    @Test
    @DisplayName("월별 데이터 사용량: month가 00이면 INVALID_MONTH")
    void getMonthlyDataUsage_zeroMonth_throws() {
        assertThatThrownBy(() -> dataService.getMonthlyDataUsage(1L, 202300))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(DataErrorCode.INVALID_MONTH));
    }

    @Test
    @DisplayName("월별 데이터 사용량: 조회 결과 없으면 DATA_NOT_FOUND")
    void getMonthlyDataUsage_empty_throws() {
        when(dataMapper.findRecentMonthlyUsageByLineId(1L, 202603)).thenReturn(List.of());

        assertThatThrownBy(() -> dataService.getMonthlyDataUsage(1L, 202603))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(DataErrorCode.DATA_NOT_FOUND));
    }

    @Test
    @DisplayName("월별 데이터 사용량: 평균 계산 포함 반환")
    void getMonthlyDataUsage_success_returnsAverage() {
        List<MonthlyUsageDto> usages = List.of(
            MonthlyUsageDto.builder().yearMonth("202603").usedAmount(100L).build(),
            MonthlyUsageDto.builder().yearMonth("202602").usedAmount(300L).build()
        );
        when(dataMapper.findRecentMonthlyUsageByLineId(1L, 202603)).thenReturn(usages);

        MonthlyDataUsageResDto result = dataService.getMonthlyDataUsage(1L, 202603);

        assertThat(result.getUsages()).isEqualTo(usages);
        assertThat(result.getAverageAmount()).isEqualTo(200L);
    }

    @Test
    @DisplayName("앱 데이터 사용량: month 형식이 잘못되면 INVALID_MONTH")
    void getAppDataUsage_invalidMonth_throws() {
        AuthUserDetails principal = principalWithLineId(2L);

        assertThatThrownBy(() -> dataService.getAppDataUsage(1L, 202313, principal))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(DataErrorCode.INVALID_MONTH));
    }

    @Test
    @DisplayName("앱 데이터 사용량: 회선 없으면 DATA_NOT_FOUND")
    void getAppDataUsage_familyLineNotFound_throws() {
        AuthUserDetails principal = principalWithLineId(2L);
        when(permissionLineMapper.isPermissionEnabledByTitle(1L)).thenReturn(true);
        when(familyLineMapper.findByLineId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dataService.getAppDataUsage(1L, 202603, principal))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(DataErrorCode.DATA_NOT_FOUND));
    }

    @Test
    @DisplayName("앱 데이터 사용량: 권한 비활성 또는 비공개면 isPublic=false 반환")
    void getAppDataUsage_permissionDisabled_returnsPrivate() {
        AuthUserDetails principal = principalWithLineId(2L);
        when(permissionLineMapper.isPermissionEnabledByTitle(1L)).thenReturn(false);
        when(familyLineMapper.findByLineId(1L)).thenReturn(Optional.of(familyLine(false)));

        AppDataUsageResDto result = dataService.getAppDataUsage(1L, 202603, principal);

        assertThat(result.getIsPublic()).isFalse();
        assertThat(result.getTotalUsedAmount()).isNull();
        assertThat(result.getApps()).isNull();
    }

    @Test
    @DisplayName("앱 데이터 사용량: 공개 권한 있어도 비공개면 isPublic=false 반환")
    void getAppDataUsage_notPublic_returnsPrivate() {
        AuthUserDetails principal = principalWithLineId(2L);
        when(permissionLineMapper.isPermissionEnabledByTitle(1L)).thenReturn(true);
        when(familyLineMapper.findByLineId(1L)).thenReturn(Optional.of(familyLine(false)));

        AppDataUsageResDto result = dataService.getAppDataUsage(1L, 202603, principal);

        assertThat(result.getIsPublic()).isFalse();
    }

    @Test
    @DisplayName("앱 데이터 사용량: 앱 목록 비어있으면 DATA_NOT_FOUND")
    void getAppDataUsage_emptyApps_throws() {
        AuthUserDetails principal = principalWithLineId(2L);
        when(permissionLineMapper.isPermissionEnabledByTitle(1L)).thenReturn(true);
        when(familyLineMapper.findByLineId(1L)).thenReturn(Optional.of(familyLine(true)));
        when(dataMapper.findAppDataUsageByLineIdAndMonth(1L, 202603)).thenReturn(List.of());

        assertThatThrownBy(() -> dataService.getAppDataUsage(1L, 202603, principal))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(DataErrorCode.DATA_NOT_FOUND));
    }

    @Test
    @DisplayName("앱 데이터 사용량: 총 사용량 합산 반환")
    void getAppDataUsage_success_returnsTotal() {
        AuthUserDetails principal = principalWithLineId(2L);
        List<AppDataUsageResDto.AppUsageDto> apps = List.of(
            AppDataUsageResDto.AppUsageDto.builder().appName("A").usedAmount(100L).build(),
            AppDataUsageResDto.AppUsageDto.builder().appName("B").usedAmount(300L).build()
        );
        when(permissionLineMapper.isPermissionEnabledByTitle(1L)).thenReturn(true);
        when(familyLineMapper.findByLineId(1L)).thenReturn(Optional.of(familyLine(true)));
        when(dataMapper.findAppDataUsageByLineIdAndMonth(1L, 202603)).thenReturn(apps);

        AppDataUsageResDto result = dataService.getAppDataUsage(1L, 202603, principal);

        assertThat(result.getIsPublic()).isTrue();
        assertThat(result.getTotalUsedAmount()).isEqualTo(400L);
        assertThat(result.getApps()).isEqualTo(apps);
    }

    @Test
    @DisplayName("데이터 요약: 결과 없으면 DATA_NOT_FOUND")
    void getDataSummary_null_throws() {
        when(dataMapper.findDataSummaryByLineId(1L)).thenReturn(null);

        assertThatThrownBy(() -> dataService.getDataSummary(1L))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(DataErrorCode.DATA_NOT_FOUND));
    }

    @Test
    @DisplayName("데이터 요약: Redis 버퍼 잔여량을 합산해서 반환")
    void getDataSummary_success_returnsMergedBalances() {
        YearMonth targetMonth = YearMonth.of(2026, 3);
        DataBalancesResDto dto = DataBalancesResDto.builder()
            .lineId(1L)
            .userName("user")
            .role("OWNER")
            .sharedDataRemaining(100L)
            .personalDataRemaining(50L)
            .planName("plan")
            .build();
        when(dataMapper.findDataSummaryByLineId(1L)).thenReturn(dto);
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingIndivAmountKey(1L, targetMonth))
            .thenReturn("pooli:remaining_indiv_amount:1:202603");
        when(familyLineMapper.findByLineId(1L)).thenReturn(Optional.of(familyLine(true)));
        when(trafficRedisKeyFactory.remainingSharedAmountKey(1L, targetMonth))
            .thenReturn("pooli:remaining_shared_amount:1:202603");
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("pooli:remaining_indiv_amount:1:202603", "amount")).thenReturn("20");
        when(hashOperations.get("pooli:remaining_shared_amount:1:202603", "amount")).thenReturn("30");

        DataBalancesResDto result = dataService.getDataSummary(1L);

        assertThat(result.getLineId()).isEqualTo(1L);
        assertThat(result.getUserName()).isEqualTo("user");
        assertThat(result.getRole()).isEqualTo("OWNER");
        assertThat(result.getPersonalDataRemaining()).isEqualTo(70L);
        assertThat(result.getSharedDataRemaining()).isEqualTo(130L);
        assertThat(result.getPlanName()).isEqualTo("plan");
    }

    @Test
    @DisplayName("데이터 요약: Redis 값이 없으면 DB 값을 유지")
    void getDataSummary_whenRedisMissing_keepsDbValues() {
        YearMonth targetMonth = YearMonth.of(2026, 3);
        DataBalancesResDto dto = DataBalancesResDto.builder()
            .lineId(1L)
            .userName("user")
            .role("OWNER")
            .sharedDataRemaining(100L)
            .personalDataRemaining(50L)
            .planName("plan")
            .build();
        when(dataMapper.findDataSummaryByLineId(1L)).thenReturn(dto);
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingIndivAmountKey(1L, targetMonth))
            .thenReturn("pooli:remaining_indiv_amount:1:202603");
        when(familyLineMapper.findByLineId(1L)).thenReturn(Optional.of(familyLine(true)));
        when(trafficRedisKeyFactory.remainingSharedAmountKey(1L, targetMonth))
            .thenReturn("pooli:remaining_shared_amount:1:202603");
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("pooli:remaining_indiv_amount:1:202603", "amount")).thenReturn(null);
        when(hashOperations.get("pooli:remaining_shared_amount:1:202603", "amount")).thenReturn(null);

        DataBalancesResDto result = dataService.getDataSummary(1L);

        assertThat(result.getPersonalDataRemaining()).isEqualTo(50L);
        assertThat(result.getSharedDataRemaining()).isEqualTo(100L);
    }

    @Test
    @DisplayName("데이터 사용량: yearMonth 형식이 잘못되면 INVALID_MONTH")
    void getDataUsage_invalidMonth_throws() {
        assertThatThrownBy(() -> dataService.getDataUsage(1L, 202313))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(DataErrorCode.INVALID_MONTH));
    }

    @Test
    @DisplayName("데이터 사용량: 조회 결과 없으면 DATA_NOT_FOUND")
    void getDataUsage_null_throws() {
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(dataMapper.findDataUsageAggregateByLineIdAndMonth(1L, 202603)).thenReturn(null);

        assertThatThrownBy(() -> dataService.getDataUsage(1L, 202603))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(DataErrorCode.DATA_NOT_FOUND));
    }

    @Test
    @DisplayName("데이터 사용량: 현재 월이면 총량 포함 반환")
    void getDataUsage_currentMonth_returnsTotals() {
        int yearMonth = currentYearMonth();
        YearMonth targetMonth = YearMonth.now(ZoneId.of("Asia/Seoul"));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        DataUsageResDto row = DataUsageResDto.builder()
            .personalUsedAmount(10L)
            .sharedPoolUsedAmount(20L)
            .personalTotalAmount(100L)
            .sharedPoolTotalAmount(200L)
            .build();
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(dataMapper.findDataUsageAggregateByLineIdAndMonth(1L, yearMonth)).thenReturn(row);
        when(dataMapper.findDailyTotalUsageByLineIdAndDate(1L, today)).thenReturn(15L);
        when(trafficRedisKeyFactory.dailyTotalUsageKey(1L, today))
            .thenReturn("pooli:daily_total_usage:1:" + today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
        when(trafficRedisKeyFactory.monthlySharedUsageKey(1L, targetMonth))
            .thenReturn("pooli:monthly_shared_usage:1:" + targetMonth.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")));
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("pooli:daily_total_usage:1:" + today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)))
            .thenReturn("25");
        when(valueOperations.get("pooli:monthly_shared_usage:1:" + targetMonth.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))))
            .thenReturn("30");
        when(familySharedPoolsService.resolveFamilyMemberMonthlySharedPoolDisplay(1L))
            .thenReturn(FamilyMembersResDto.FamilyMemberDto.builder()
                .lineId(1)
                .remainingData(95L)
                .basicDataAmount(120L)
                .sharedPoolTotalAmount(200L)
                .sharedPoolRemainingAmount(170L)
                .build());

        DataUsageResDto result = dataService.getDataUsage(1L, yearMonth);

        assertThat(result.getIsCurrentMonth()).isTrue();
        assertThat(result.getPersonalUsedAmount()).isEqualTo(25L);
        assertThat(result.getSharedPoolUsedAmount()).isEqualTo(30L);
        assertThat(result.getPersonalTotalAmount()).isEqualTo(120L);
        assertThat(result.getSharedPoolTotalAmount()).isEqualTo(200L);
        assertThat(result.getSharedPoolRemainingAmount()).isEqualTo(170L);
    }

    @Test
    @DisplayName("데이터 사용량: 현재 월 무제한 공유 한도면 잔여량 필드는 유지하고 총량은 -1로 보존")
    void getDataUsage_currentMonth_unlimitedSharedLimit_preservesUnlimitedTotal() {
        int yearMonth = currentYearMonth();
        YearMonth targetMonth = YearMonth.now(ZoneId.of("Asia/Seoul"));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        DataUsageResDto row = DataUsageResDto.builder()
            .personalUsedAmount(10L)
            .sharedPoolUsedAmount(20L)
            .personalTotalAmount(100L)
            .sharedPoolTotalAmount(200L)
            .build();
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(dataMapper.findDataUsageAggregateByLineIdAndMonth(1L, yearMonth)).thenReturn(row);
        when(dataMapper.findDailyTotalUsageByLineIdAndDate(1L, today)).thenReturn(15L);
        when(trafficRedisKeyFactory.dailyTotalUsageKey(1L, today))
            .thenReturn("pooli:daily_total_usage:1:" + today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
        when(trafficRedisKeyFactory.monthlySharedUsageKey(1L, targetMonth))
            .thenReturn("pooli:monthly_shared_usage:1:" + targetMonth.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")));
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("pooli:daily_total_usage:1:" + today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)))
            .thenReturn("25");
        when(valueOperations.get("pooli:monthly_shared_usage:1:" + targetMonth.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))))
            .thenReturn("30");
        when(familySharedPoolsService.resolveFamilyMemberMonthlySharedPoolDisplay(1L))
            .thenReturn(FamilyMembersResDto.FamilyMemberDto.builder()
                .lineId(1)
                .sharedPoolTotalAmount(-1L)
                .sharedPoolRemainingAmount(170L)
                .build());

        DataUsageResDto result = dataService.getDataUsage(1L, yearMonth);

        assertThat(result.getSharedPoolUsedAmount()).isEqualTo(30L);
        assertThat(result.getSharedPoolTotalAmount()).isEqualTo(-1L);
        assertThat(result.getSharedPoolRemainingAmount()).isEqualTo(170L);
    }

    @Test
    @DisplayName("데이터 사용량: 현재 월이 아니면 총량은 null")
    void getDataUsage_notCurrentMonth_returnsNullTotals() {
        int yearMonth = previousYearMonth();
        DataUsageResDto row = DataUsageResDto.builder()
            .personalUsedAmount(10L)
            .sharedPoolUsedAmount(20L)
            .personalTotalAmount(100L)
            .sharedPoolTotalAmount(200L)
            .build();
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(dataMapper.findDataUsageAggregateByLineIdAndMonth(1L, yearMonth)).thenReturn(row);

        DataUsageResDto result = dataService.getDataUsage(1L, yearMonth);

        assertThat(result.getIsCurrentMonth()).isFalse();
        assertThat(result.getPersonalTotalAmount()).isNull();
        assertThat(result.getSharedPoolTotalAmount()).isNull();
        assertThat(result.getSharedPoolRemainingAmount()).isNull();
    }

    private static FamilyLine familyLine(boolean isPublic) {
        return FamilyLine.builder()
            .familyId(1L)
            .lineId(1L)
            .isPublic(isPublic)
            .build();
    }

    private static int currentYearMonth() {
        YearMonth now = YearMonth.now();
        return now.getYear() * 100 + now.getMonthValue();
    }

    private static int previousYearMonth() {
        YearMonth prev = YearMonth.now().minusMonths(1);
        return prev.getYear() * 100 + prev.getMonthValue();
    }

    private static AuthUserDetails principalWithLineId(Long lineId) {
        return AuthUserDetails.builder()
            .userId(1L)
            .userName("user")
            .email("user@example.com")
            .password("pw")
            .lineId(lineId)
            .authorities(List.of())
            .build();
    }
}
