package com.pooli.data.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.common.exception.ApplicationException;
import com.pooli.data.domain.dto.response.AppDataUsageResDto;
import com.pooli.data.domain.dto.response.DataBalancesResDto;
import com.pooli.data.domain.dto.response.DataUsageResDto;
import com.pooli.data.domain.dto.response.MonthlyDataUsageResDto;
import com.pooli.data.domain.dto.response.MonthlyDataUsageResDto.MonthlyUsageDto;
import com.pooli.data.error.DataErrorCode;
import com.pooli.data.mapper.DataMapper;
import com.pooli.family.domain.entity.FamilyLine;
import com.pooli.permission.mapper.FamilyLineMapper;
import com.pooli.permission.mapper.PermissionLineMapper;

@ExtendWith(MockitoExtension.class)
class DataServiceImplTest {

    @Mock
    private DataMapper dataMapper;

    @Mock
    private PermissionLineMapper permissionLineMapper;

    @Mock
    private FamilyLineMapper familyLineMapper;

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
        assertThatThrownBy(() -> dataService.getAppDataUsage(1L, 202313))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(DataErrorCode.INVALID_MONTH));
    }

    @Test
    @DisplayName("앱 데이터 사용량: 회선 없음이면 DATA_NOT_FOUND")
    void getAppDataUsage_familyLineNotFound_throws() {
        when(permissionLineMapper.isPermissionEnabledByTitle(1L)).thenReturn(true);
        when(familyLineMapper.findByLineId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dataService.getAppDataUsage(1L, 202603))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(DataErrorCode.DATA_NOT_FOUND));
    }

    @Test
    @DisplayName("앱 데이터 사용량: 권한 비활성 또는 비공개면 isPublic=false 반환")
    void getAppDataUsage_permissionDisabled_returnsPrivate() {
        when(permissionLineMapper.isPermissionEnabledByTitle(1L)).thenReturn(false);
        when(familyLineMapper.findByLineId(1L)).thenReturn(Optional.of(familyLine(false)));

        AppDataUsageResDto result = dataService.getAppDataUsage(1L, 202603);

        assertThat(result.getIsPublic()).isFalse();
        assertThat(result.getTotalUsedAmount()).isNull();
        assertThat(result.getApps()).isNull();
    }

    @Test
    @DisplayName("앱 데이터 사용량: 권한 활성이나 비공개면 isPublic=false 반환")
    void getAppDataUsage_notPublic_returnsPrivate() {
        when(permissionLineMapper.isPermissionEnabledByTitle(1L)).thenReturn(true);
        when(familyLineMapper.findByLineId(1L)).thenReturn(Optional.of(familyLine(false)));

        AppDataUsageResDto result = dataService.getAppDataUsage(1L, 202603);

        assertThat(result.getIsPublic()).isFalse();
    }

    @Test
    @DisplayName("앱 데이터 사용량: 앱 목록 비어있으면 DATA_NOT_FOUND")
    void getAppDataUsage_emptyApps_throws() {
        when(permissionLineMapper.isPermissionEnabledByTitle(1L)).thenReturn(true);
        when(familyLineMapper.findByLineId(1L)).thenReturn(Optional.of(familyLine(true)));
        when(dataMapper.findAppDataUsageByLineIdAndMonth(1L, 202603)).thenReturn(List.of());

        assertThatThrownBy(() -> dataService.getAppDataUsage(1L, 202603))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(DataErrorCode.DATA_NOT_FOUND));
    }

    @Test
    @DisplayName("앱 데이터 사용량: 앱 사용량 합산 반환")
    void getAppDataUsage_success_returnsTotal() {
        List<AppDataUsageResDto.AppUsageDto> apps = List.of(
            AppDataUsageResDto.AppUsageDto.builder().appName("A").usedAmount(100L).build(),
            AppDataUsageResDto.AppUsageDto.builder().appName("B").usedAmount(300L).build()
        );
        when(permissionLineMapper.isPermissionEnabledByTitle(1L)).thenReturn(true);
        when(familyLineMapper.findByLineId(1L)).thenReturn(Optional.of(familyLine(true)));
        when(dataMapper.findAppDataUsageByLineIdAndMonth(1L, 202603)).thenReturn(apps);

        AppDataUsageResDto result = dataService.getAppDataUsage(1L, 202603);

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
    @DisplayName("데이터 요약: 값 반환")
    void getDataSummary_success_returnsDto() {
        DataBalancesResDto dto = DataBalancesResDto.builder()
            .userName("user")
            .role("OWNER")
            .sharedDataRemaining(100L)
            .personalDataRemaining(50L)
            .planName("plan")
            .build();
        when(dataMapper.findDataSummaryByLineId(1L)).thenReturn(dto);

        DataBalancesResDto result = dataService.getDataSummary(1L);

        assertThat(result).isEqualTo(dto);
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
        DataUsageResDto row = DataUsageResDto.builder()
            .personalUsedAmount(10L)
            .sharedPoolUsedAmount(20L)
            .personalTotalAmount(100L)
            .sharedPoolTotalAmount(200L)
            .build();
        when(dataMapper.findDataUsageAggregateByLineIdAndMonth(1L, yearMonth)).thenReturn(row);

        DataUsageResDto result = dataService.getDataUsage(1L, yearMonth);

        assertThat(result.getIsCurrentMonth()).isTrue();
        assertThat(result.getPersonalTotalAmount()).isEqualTo(100L);
        assertThat(result.getSharedPoolTotalAmount()).isEqualTo(200L);
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
        when(dataMapper.findDataUsageAggregateByLineIdAndMonth(1L, yearMonth)).thenReturn(row);

        DataUsageResDto result = dataService.getDataUsage(1L, yearMonth);

        assertThat(result.getIsCurrentMonth()).isFalse();
        assertThat(result.getPersonalTotalAmount()).isNull();
        assertThat(result.getSharedPoolTotalAmount()).isNull();
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
}
