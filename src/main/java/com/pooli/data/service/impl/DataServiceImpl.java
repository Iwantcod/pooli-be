package com.pooli.data.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.data.domain.dto.response.AppDataUsageResDto;
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

    private static final String PERMISSION_TITLE_PUBLIC_INFO = "가족원 정보 공개 여부";

    private void validateMonth(Integer month) {
        if (month == null || month < 100001 || month > 999912) {
            throw new ApplicationException(DataErrorCode.INVALID_MONTH);
        }
        int mm = month % 100;
        if (mm < 1 || mm > 12) {
            throw new ApplicationException(DataErrorCode.INVALID_MONTH);
        }
    }
	
	@Override
	public MonthlyDataUsageResDto getMonthlyDataUsage(Long lineId, Integer month) {
		validateMonth(month);

		List<MonthlyUsageDto> usages = dataMapper.findRecentMonthlyUsageByLineId(lineId, month);
		
		
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

        Boolean permissionEnabled = permissionLineMapper.isPermissionEnabledByTitle(lineId, PERMISSION_TITLE_PUBLIC_INFO);

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

}
