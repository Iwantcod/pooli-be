package com.pooli.data.service.impl;

import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.data.domain.dto.response.AppDataUsageResDto;
import com.pooli.data.domain.dto.response.DataBalancesResDto;
import com.pooli.data.domain.dto.response.DataUsageResDto;
import com.pooli.data.domain.dto.response.MonthlyDataUsageResDto;
import com.pooli.data.domain.dto.response.MonthlyDataUsageResDto.MonthlyUsageDto;
import com.pooli.data.error.DataErrorCode;
import com.pooli.data.mapper.DataMapper;
import com.pooli.data.service.DataService;
import com.pooli.family.domain.entity.FamilyLine;
import com.pooli.permission.mapper.FamilyLineMapper;
import com.pooli.permission.mapper.PermissionLineMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DataServiceImpl implements DataService {
	
	private final DataMapper dataMapper;
    private final PermissionLineMapper permissionLineMapper;
    private final FamilyLineMapper familyLineMapper;


	
	@Override
	public MonthlyDataUsageResDto getMonthlyDataUsage(Long lineId, Integer yearMonth) {
		validateMonth(yearMonth);

		List<MonthlyUsageDto> usages = dataMapper.findRecentMonthlyUsageByLineId(lineId, yearMonth);
		
		
		if(usages.isEmpty()) {
			throw new ApplicationException(DataErrorCode.DATA_NOT_FOUND);
		}
		
		
		Long average = usages.stream()
                .mapToLong(MonthlyDataUsageResDto.MonthlyUsageDto::getUsedAmount)
                .sum() / usages.size();
		
		MonthlyDataUsageResDto response = MonthlyDataUsageResDto.builder()
                .usages(usages)
                .averageAmount(average)
                .build();
	
		return response;
	}

	@Override
	public AppDataUsageResDto getAppDataUsage(Long lineId, Integer month) {

        validateMonth(month);

        Boolean permissionEnabled = permissionLineMapper.isPermissionEnabledByTitle(lineId);

        FamilyLine familyLine = familyLineMapper.findByLineId(lineId)
                .orElseThrow(() -> new ApplicationException(DataErrorCode.DATA_NOT_FOUND));

        if (!Boolean.TRUE.equals(permissionEnabled) || !Boolean.TRUE.equals(familyLine.getIsPublic())) {
            return AppDataUsageResDto.builder()
                    .isPublic(false)
                    .totalUsedAmount(null)
                    .apps(null)
                    .build();
        }

		List<AppDataUsageResDto.AppUsageDto> apps = dataMapper.findAppDataUsageByLineIdAndMonth(lineId, month);
		
		if (apps.isEmpty()) {
			throw new ApplicationException(DataErrorCode.DATA_NOT_FOUND);
		}

		Long total = apps.stream()
                .mapToLong(AppDataUsageResDto.AppUsageDto::getUsedAmount)
                .sum();

        AppDataUsageResDto response = AppDataUsageResDto.builder()
                .isPublic(true)
                .totalUsedAmount(total)
                .apps(apps)
                .build();
		
		return response;
	}
	
	@Override
	public DataBalancesResDto getDataSummary(Long lineId) {
		DataBalancesResDto response = dataMapper.findDataSummaryByLineId(lineId);
		
		if(response == null) {
			throw new ApplicationException(DataErrorCode.DATA_NOT_FOUND);
		}
		
		return response;
	}
	
	@Override
	public DataUsageResDto getDataUsage(Long lineId, Integer yearMonth) {
		validateMonth(yearMonth);

	      boolean isCurrentMonth = YearMonth.now().equals(
	              YearMonth.of(yearMonth / 100, yearMonth % 100)
	      );

	      DataUsageResDto row = dataMapper.findDataUsageAggregateByLineIdAndMonth(lineId, yearMonth);
	      if (row == null) {
	          throw new ApplicationException(DataErrorCode.DATA_NOT_FOUND);
	      }

	      Long personalTotalAmount = isCurrentMonth ? row.getPersonalTotalAmount() : null;
	      Long sharedPoolTotalAmount = isCurrentMonth ? row.getSharedPoolTotalAmount() : null;

	      return DataUsageResDto.builder()
	              .isCurrentMonth(isCurrentMonth)
	              .personalUsedAmount(row.getPersonalUsedAmount())
	              .sharedPoolUsedAmount(row.getSharedPoolUsedAmount())
	              .personalTotalAmount(personalTotalAmount)
	              .sharedPoolTotalAmount(sharedPoolTotalAmount)
	              .build();
	}
	
    private void validateMonth(Integer yearMonth) {
        if (yearMonth == null || yearMonth < 100001 || yearMonth > 999912) {
            throw new ApplicationException(DataErrorCode.INVALID_MONTH);
        }
        int mm = yearMonth % 100;
        if (mm < 1 || mm > 12) {
            throw new ApplicationException(DataErrorCode.INVALID_MONTH);
        }
    }





}
