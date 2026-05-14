package com.pooli.data.service.impl;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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
import com.pooli.family.domain.dto.response.FamilyMembersResDto;
import com.pooli.family.domain.entity.FamilyLine;
import com.pooli.family.service.FamilySharedPoolsService;
import com.pooli.permission.mapper.FamilyLineMapper;
import com.pooli.permission.mapper.PermissionLineMapper;
import com.pooli.traffic.service.runtime.TrafficRemainingBalanceQueryService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DataServiceImpl implements DataService {
	
	private final DataMapper dataMapper;
    private final PermissionLineMapper permissionLineMapper;
    private final FamilyLineMapper familyLineMapper;
    private final FamilySharedPoolsService familySharedPoolsService;
    private final TrafficRemainingBalanceQueryService trafficRemainingBalanceQueryService;
    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;


	
    /**
     * 최근 월별 사용량 목록을 조회하고, 조회된 월들의 평균 사용량을 계산합니다.
     */
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

    /**
     * 앱별 사용량을 공개 범위 권한에 맞게 조회합니다.
     *
     * <p>비공개 회선은 본인만 상세 사용량을 볼 수 있고, 권한이 없으면 공개 여부만 반환합니다.
     */
	@Override
	public AppDataUsageResDto getAppDataUsage(Long lineId, Integer month, AuthUserDetails principal) {

        validateMonth(month);

        Boolean permissionEnabled = permissionLineMapper.isPermissionEnabledByTitle(lineId);

        FamilyLine familyLine = familyLineMapper.findByLineId(lineId)
                .orElseThrow(() -> new ApplicationException(DataErrorCode.DATA_NOT_FOUND));
        
        boolean canUsePrivacy = Boolean.TRUE.equals(permissionEnabled);
        boolean isPublic = !canUsePrivacy || Boolean.TRUE.equals(familyLine.getIsPublic());
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
	
    /**
     * 데이터 요약 정보를 조회한 뒤 Redis amount-only 잔량으로 개인/공유 잔량을 보정합니다.
     */
	@Override
	public DataBalancesResDto getDataSummary(Long lineId) {
		DataBalancesResDto response = dataMapper.findDataSummaryByLineId(lineId);
		
		if(response == null) {
			throw new ApplicationException(DataErrorCode.DATA_NOT_FOUND);
		}
		
		return applyTrafficCachedBalances(response);
	}
	
    /**
     * 월별 개인/공유 사용량을 조회합니다.
     *
     * <p>현재월은 Redis의 당일/월간 사용량과 실제 잔량 표시값을 반영해 아직 DB 집계에 반영되지 않은 사용량을 보정합니다.
     */
	@Override
	public DataUsageResDto getDataUsage(Long lineId, Integer yearMonth) {
		validateMonth(yearMonth);

	      boolean isCurrentMonth = YearMonth.now(trafficRedisRuntimePolicy.zoneId()).equals(
	              YearMonth.of(yearMonth / 100, yearMonth % 100)
	      );

	      DataUsageResDto row = dataMapper.findDataUsageAggregateByLineIdAndMonth(lineId, yearMonth);
	      if (row == null) {
	          throw new ApplicationException(DataErrorCode.DATA_NOT_FOUND);
	      }

          long personalUsedAmount = normalizeNonNegative(row.getPersonalUsedAmount());
          long sharedPoolUsedAmount = normalizeNonNegative(row.getSharedPoolUsedAmount());
          Long personalTotalAmount = isCurrentMonth ? normalizeTotalAmount(row.getPersonalTotalAmount()) : null;
          Long sharedPoolTotalAmount = isCurrentMonth ? normalizeTotalAmount(row.getSharedPoolTotalAmount()) : null;
          Long sharedPoolRemainingAmount = null;

          if (isCurrentMonth) {
              LocalDate today = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
              YearMonth targetMonth = YearMonth.from(today);

              long dbTotalUsage = safeAdd(personalUsedAmount, sharedPoolUsedAmount);
              long dbTodayTotalUsage = normalizeNonNegative(
                      dataMapper.findDailyTotalUsageByLineIdAndDate(lineId, today)
              );
              long redisTodayTotalUsage = readDailyTotalUsageFromRedis(lineId, today);
              long redisMonthlySharedUsage = readMonthlySharedUsageFromRedis(lineId, targetMonth);

              long adjustedTotalUsage = safeAdd(
                      clampNonNegative(dbTotalUsage - dbTodayTotalUsage),
                      Math.max(dbTodayTotalUsage, redisTodayTotalUsage)
              );
              long adjustedSharedUsage = Math.max(sharedPoolUsedAmount, redisMonthlySharedUsage);
              adjustedTotalUsage = Math.max(adjustedTotalUsage, adjustedSharedUsage);

              sharedPoolUsedAmount = adjustedSharedUsage;
              personalUsedAmount = clampNonNegative(adjustedTotalUsage - adjustedSharedUsage);

              FamilyMembersResDto.FamilyMemberDto currentDisplay =
                      familySharedPoolsService.resolveFamilyMemberActualDisplay(lineId);
              personalTotalAmount = normalizeTotalAmount(currentDisplay.getBasicDataAmount());
              Long personalRemainingAmount = normalizeRemainingAmount(currentDisplay.getRemainingData());
              sharedPoolTotalAmount = normalizeTotalAmount(currentDisplay.getSharedPoolTotalAmount());
              sharedPoolRemainingAmount = normalizeRemainingAmount(currentDisplay.getSharedPoolRemainingAmount());

              if (personalTotalAmount != null
                      && personalRemainingAmount != null
                      && personalTotalAmount >= 0L) {
                  personalUsedAmount = clampNonNegative(personalTotalAmount - personalRemainingAmount);
              }

              if (sharedPoolTotalAmount != null
                      && sharedPoolRemainingAmount != null
                      && sharedPoolTotalAmount >= 0L) {
                  sharedPoolUsedAmount = clampNonNegative(sharedPoolTotalAmount - sharedPoolRemainingAmount);
              }
          }

	      return DataUsageResDto.builder()
	              .isCurrentMonth(isCurrentMonth)
	              .personalUsedAmount(personalUsedAmount)
	              .sharedPoolUsedAmount(sharedPoolUsedAmount)
	              .personalTotalAmount(personalTotalAmount)
	              .sharedPoolTotalAmount(sharedPoolTotalAmount)
                  .sharedPoolRemainingAmount(sharedPoolRemainingAmount)
	              .build();
	}
	
    /**
     * `yyyyMM` 형식과 월 범위를 검증하고, 유효하지 않으면 데이터 도메인 예외를 던집니다.
     */
    private void validateMonth(Integer yearMonth) {
        if (yearMonth == null || yearMonth < 100001 || yearMonth > 999912) {
            throw new ApplicationException(DataErrorCode.INVALID_MONTH);
        }
        int mm = yearMonth % 100;
        if (mm < 1 || mm > 12) {
            throw new ApplicationException(DataErrorCode.INVALID_MONTH);
        }
    }

    /**
     * DB 요약 응답의 표시 메타데이터는 유지하고, 잔량 필드는 Redis amount-only 조회 결과로 교체합니다.
     */
    private DataBalancesResDto applyTrafficCachedBalances(DataBalancesResDto response) {
        if (response == null || response.getLineId() == null || response.getLineId() <= 0) {
            return response;
        }

        Long personalRemainingAmount =
                trafficRemainingBalanceQueryService.resolveIndividualActualRemaining(response.getLineId());
        Long sharedRemainingAmount = familyLineMapper.findByLineId(response.getLineId())
                .map(FamilyLine::getFamilyId)
                .map(trafficRemainingBalanceQueryService::resolveSharedActualRemaining)
                .orElse(null);

        return DataBalancesResDto.builder()
                .lineId(response.getLineId())
                .userName(response.getUserName())
                .role(response.getRole())
                .sharedDataRemaining(sharedRemainingAmount)
                .personalDataRemaining(personalRemainingAmount)
                .planName(response.getPlanName())
                .build();
    }

    /**
     * 사용량 합산 시 null과 음수를 0으로 정규화하고 long overflow는 최댓값으로 포화시킵니다.
     */
    private long safeAdd(Long baseValue, long additionalValue) {
        long normalizedBaseValue = baseValue == null ? 0L : Math.max(0L, baseValue);
        long normalizedAdditionalValue = Math.max(0L, additionalValue);
        if (normalizedBaseValue > Long.MAX_VALUE - normalizedAdditionalValue) {
            return Long.MAX_VALUE;
        }
        return normalizedBaseValue + normalizedAdditionalValue;
    }

    /**
     * Redis에 기록된 특정 회선의 일별 총 사용량 counter를 읽습니다.
     */
    private long readDailyTotalUsageFromRedis(long lineId, LocalDate usageDate) {
        String key = trafficRedisKeyFactory.dailyTotalUsageKey(lineId, usageDate);
        return readValueLong(key);
    }

    /**
     * Redis에 기록된 특정 회선의 월별 공유풀 사용량 counter를 읽습니다.
     */
    private long readMonthlySharedUsageFromRedis(long lineId, YearMonth targetMonth) {
        String key = trafficRedisKeyFactory.monthlySharedUsageKey(lineId, targetMonth);
        return readValueLong(key);
    }

    /**
     * Redis string counter를 long으로 읽고, 누락/파싱 실패/Redis 오류는 사용량 보정 제외를 의미하는 0으로 처리합니다.
     */
    private long readValueLong(String key) {
        try {
            ValueOperations<String, String> valueOperations = cacheStringRedisTemplate.opsForValue();
            String rawValue = valueOperations.get(key);
            if (rawValue == null) {
                return 0L;
            }

            long parsed = Long.parseLong(rawValue);
            return Math.max(0L, parsed);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /**
     * DB 집계 사용량의 null/음수 값을 표시 계산에 안전한 0 이상 값으로 변환합니다.
     */
    private long normalizeNonNegative(Long value) {
        if (value == null || value <= 0L) {
            return 0L;
        }
        return value;
    }

    /**
     * 총 제공량 표시값을 정규화하며, 음수는 무제한 sentinel `-1`로 유지합니다.
     */
    private Long normalizeTotalAmount(Long value) {
        if (value == null) {
            return null;
        }
        if (value < 0L) {
            return -1L;
        }
        return Math.max(0L, value);
    }

    /**
     * 잔량 표시값을 정규화하며, 음수는 무제한 sentinel `-1`로 유지합니다.
     */
    private Long normalizeRemainingAmount(Long value) {
        if (value == null) {
            return null;
        }
        if (value < 0L) {
            return -1L;
        }
        return Math.max(0L, value);
    }

    /**
     * 계산 중 음수로 내려간 사용량을 화면 표시 가능한 0 이상 값으로 고정합니다.
     */
    private long clampNonNegative(long value) {
        return Math.max(0L, value);
    }
}
