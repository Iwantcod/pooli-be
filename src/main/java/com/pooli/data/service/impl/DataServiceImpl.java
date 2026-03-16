package com.pooli.data.service.impl;

import java.time.YearMonth;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.auth.service.AuthUserDetails;
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
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DataServiceImpl implements DataService {
	
	private final DataMapper dataMapper;
    private final PermissionLineMapper permissionLineMapper;
    private final FamilyLineMapper familyLineMapper;
    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;


	
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
	public AppDataUsageResDto getAppDataUsage(Long lineId, Integer month, AuthUserDetails principal) {

        validateMonth(month);

        Boolean permissionEnabled = permissionLineMapper.isPermissionEnabledByTitle(lineId);

        FamilyLine familyLine = familyLineMapper.findByLineId(lineId)
                .orElseThrow(() -> new ApplicationException(DataErrorCode.DATA_NOT_FOUND));
        
        boolean isPublic = Boolean.TRUE.equals(permissionEnabled) && Boolean.TRUE.equals(familyLine.getIsPublic());
        boolean isSelf = principal.getLineId() != null && principal.getLineId().equals(lineId);

        if (!isPublic && !isSelf) {
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
                .isPublic(isPublic)
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
		
		return applyTrafficCachedBalances(response);
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

    private DataBalancesResDto applyTrafficCachedBalances(DataBalancesResDto response) {
        if (response == null || response.getLineId() == null || response.getLineId() <= 0) {
            return response;
        }

        YearMonth targetMonth = YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        long personalBufferedAmount = readIndividualBufferedAmount(response.getLineId(), targetMonth);
        long sharedBufferedAmount = readSharedBufferedAmount(response.getLineId(), targetMonth);

        return DataBalancesResDto.builder()
                .lineId(response.getLineId())
                .userName(response.getUserName())
                .role(response.getRole())
                .sharedDataRemaining(safeAdd(response.getSharedDataRemaining(), sharedBufferedAmount))
                .personalDataRemaining(safeAdd(response.getPersonalDataRemaining(), personalBufferedAmount))
                .planName(response.getPlanName())
                .build();
    }

    private long readIndividualBufferedAmount(long lineId, YearMonth targetMonth) {
        String key = trafficRedisKeyFactory.remainingIndivAmountKey(lineId, targetMonth);
        return readHashLong(key, "amount");
    }

    private long readSharedBufferedAmount(long lineId, YearMonth targetMonth) {
        Long familyId = familyLineMapper.findByLineId(lineId)
                .map(FamilyLine::getFamilyId)
                .orElse(null);
        if (familyId == null || familyId <= 0) {
            return 0L;
        }

        String key = trafficRedisKeyFactory.remainingSharedAmountKey(familyId, targetMonth);
        return readHashLong(key, "amount");
    }

    private long readHashLong(String key, String field) {
        try {
            HashOperations<String, Object, Object> hashOperations = cacheStringRedisTemplate.opsForHash();
            Object rawValue = hashOperations.get(key, field);
            if (rawValue == null) {
                return 0L;
            }

            long parsed = Long.parseLong(String.valueOf(rawValue));
            return Math.max(0L, parsed);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private long safeAdd(Long baseValue, long additionalValue) {
        long normalizedBaseValue = baseValue == null ? 0L : Math.max(0L, baseValue);
        long normalizedAdditionalValue = Math.max(0L, additionalValue);
        if (normalizedBaseValue > Long.MAX_VALUE - normalizedAdditionalValue) {
            return Long.MAX_VALUE;
        }
        return normalizedBaseValue + normalizedAdditionalValue;
    }
}
