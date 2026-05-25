package com.pooli.data.service.impl;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
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
        // 1. 요청 월 형식을 먼저 검증해 잘못된 조회가 mapper까지 내려가지 않게 합니다.
		validateMonth(yearMonth);

        // 2. 기준 월 이전의 최근 월별 사용량 row를 조회합니다.
		List<MonthlyUsageDto> usages = dataMapper.findRecentMonthlyUsageByLineId(lineId, yearMonth);
		
        // 3. 표시할 사용량 row가 없으면 데이터 없음으로 응답합니다.
		if(usages.isEmpty()) {
			throw new ApplicationException(DataErrorCode.DATA_NOT_FOUND);
		}
		
        // 4. 조회된 월별 사용량의 산술 평균을 응답 요약값으로 계산합니다.
		Long average = usages.stream()
                .mapToLong(MonthlyDataUsageResDto.MonthlyUsageDto::getUsedAmount)
                .sum() / usages.size();
		
        // 5. 원본 월별 목록과 평균을 함께 담아 반환합니다.
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

        // 1. 앱별 사용량 조회도 월 단위 요청이므로 yyyyMM 형식을 먼저 검증합니다.
        validateMonth(month);

        // 2. 회선별 공개/비공개 권한 정책이 켜져 있는지 확인합니다.
        Boolean permissionEnabled = permissionLineMapper.isPermissionEnabledByTitle(lineId);

        // 3. 회선의 가족 공개 상태를 조회하고, 회선이 없으면 데이터 없음으로 처리합니다.
        FamilyLine familyLine = familyLineMapper.findByLineId(lineId)
                .orElseThrow(() -> new ApplicationException(DataErrorCode.DATA_NOT_FOUND));
        
        // 4. 권한 정책, 공개 여부, 본인 여부를 조합해 상세 사용량 노출 가능 여부를 결정합니다.
        boolean canUsePrivacy = Boolean.TRUE.equals(permissionEnabled);
        boolean isPublic = !canUsePrivacy || Boolean.TRUE.equals(familyLine.getIsPublic());
        boolean isSelf = principal.getLineId() != null && principal.getLineId().equals(lineId);

        // 5. 비공개 회선을 타인이 조회하면 상세 목록 없이 공개 여부만 반환합니다.
        if (!isPublic && !isSelf) {
            return AppDataUsageResDto.builder()
                    .isPublic(false)
                    .totalUsedAmount(null)
                    .apps(null)
                    .build();
        }

        // 6. 접근 가능한 경우 앱별 사용량 집계 row를 조회합니다.
		List<AppDataUsageResDto.AppUsageDto> apps = dataMapper.findAppDataUsageByLineIdAndMonth(lineId, month);
		
        // 7. 앱별 사용량 row가 없으면 데이터 없음으로 응답합니다.
		if (apps.isEmpty()) {
			throw new ApplicationException(DataErrorCode.DATA_NOT_FOUND);
		}

        // 8. 앱별 사용량을 합산해 화면의 전체 사용량 값을 계산합니다.
		Long total = apps.stream()
                .mapToLong(AppDataUsageResDto.AppUsageDto::getUsedAmount)
                .sum();

        // 9. 공개 여부, 전체 사용량, 앱별 목록을 응답 DTO로 조립합니다.
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
        // 1. DB 기준 회선 요약 정보를 먼저 조회합니다.
		DataBalancesResDto response = dataMapper.findDataSummaryByLineId(lineId);
		
        // 2. 회선 요약 row가 없으면 데이터 없음으로 응답합니다.
		if(response == null) {
			throw new ApplicationException(DataErrorCode.DATA_NOT_FOUND);
		}
		
        // 3. DB 표시 정보는 유지하되 Redis 실시간 잔량으로 잔량 필드를 보정합니다.
		return applyTrafficCachedBalances(response);
	}
	
    /**
     * 월별 개인/공유 사용량을 조회합니다.
     *
     * <p>현재월은 Redis의 당일/월간 사용량과 실제 잔량 표시값을 반영해 아직 DB 집계에 반영되지 않은 사용량을 보정합니다.
     */
	@Override
	public DataUsageResDto getDataUsage(Long lineId, Integer yearMonth) {
        // 1. 요청 월 형식을 검증하고 현재월 여부를 계산합니다.
		validateMonth(yearMonth);

	      boolean isCurrentMonth = YearMonth.now(trafficRedisRuntimePolicy.zoneId()).equals(
	              YearMonth.of(yearMonth / 100, yearMonth % 100)
	      );

          // 2. DB에 저장된 월별 개인/공유 사용량 집계 row를 조회합니다.
	      DataUsageResDto row = dataMapper.findDataUsageAggregateByLineIdAndMonth(lineId, yearMonth);
	      if (row == null) {
	          throw new ApplicationException(DataErrorCode.DATA_NOT_FOUND);
	      }

          // 3. DB 집계값을 화면 계산에 안전한 값으로 정규화하고, 과거월은 총량/잔량 표시값을 비웁니다.
          long personalUsedAmount = normalizeNonNegative(row.getPersonalUsedAmount());
          long sharedPoolUsedAmount = normalizeNonNegative(row.getSharedPoolUsedAmount());
          Long personalTotalAmount = isCurrentMonth ? normalizeTotalAmount(row.getPersonalTotalAmount()) : null;
          Long sharedPoolTotalAmount = isCurrentMonth ? normalizeTotalAmount(row.getSharedPoolTotalAmount()) : null;
          Long sharedPoolRemainingAmount = null;

          if (isCurrentMonth) {
              // 4. 현재월은 아직 DB 배치에 반영되지 않은 당일/월간 Redis 사용량을 함께 반영합니다.
              LocalDate today = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
              YearMonth targetMonth = YearMonth.from(today);

              // 5. DB 월 누적값에서 DB 당일분을 뺀 뒤 Redis 당일 총 사용량과 합쳐 최신 총 사용량을 만듭니다.
              long dbTotalUsage = safeAdd(personalUsedAmount, sharedPoolUsedAmount);
              long dbTodayTotalUsage = normalizeNonNegative(
                      dataMapper.findDailyTotalUsageByLineIdAndDate(lineId, today)
              );
              long redisTodayTotalUsage = readDailyTotalUsageFromRedis(lineId, today);
              long redisMonthlySharedUsage = readMonthlySharedUsageFromRedis(lineId, targetMonth);

              // 6. 공유풀 사용량은 DB 월 집계와 Redis 월간 공유 사용량 중 큰 값을 사용합니다.
              long adjustedTotalUsage = safeAdd(
                      Math.max(0L, dbTotalUsage - dbTodayTotalUsage),
                      Math.max(dbTodayTotalUsage, redisTodayTotalUsage)
              );
              long adjustedSharedUsage = Math.max(sharedPoolUsedAmount, redisMonthlySharedUsage);
              adjustedTotalUsage = Math.max(adjustedTotalUsage, adjustedSharedUsage);

              // 7. 최신 총 사용량에서 공유풀 사용량을 뺀 값을 개인풀 사용량으로 재계산합니다.
              sharedPoolUsedAmount = adjustedSharedUsage;
              personalUsedAmount = Math.max(0L, adjustedTotalUsage - adjustedSharedUsage);

              // 8. 현재 표시용 총량/잔량은 Redis-only 잔량 조회 경로의 실제 표시값으로 다시 맞춥니다.
              FamilyMembersResDto.FamilyMemberDto currentDisplay =
                      familySharedPoolsService.resolveFamilyMemberActualDisplay(lineId);
              personalTotalAmount = normalizeTotalAmount(currentDisplay.getBasicDataAmount());
              Long personalRemainingAmount = normalizeRemainingAmount(currentDisplay.getRemainingData());
              sharedPoolTotalAmount = normalizeTotalAmount(currentDisplay.getSharedPoolTotalAmount());
              sharedPoolRemainingAmount = normalizeRemainingAmount(currentDisplay.getSharedPoolRemainingAmount());

              // 9. 개인풀 총량과 잔량이 모두 유효하면 총량-잔량 기준 사용량을 최종 표시값으로 사용합니다.
              if (personalTotalAmount != null
                      && personalRemainingAmount != null
                      && personalTotalAmount >= 0L) {
                  personalUsedAmount = Math.max(0L, personalTotalAmount - personalRemainingAmount);
              }

              // 10. 공유풀 총량과 잔량이 모두 유효하면 총량-잔량 기준 사용량을 최종 표시값으로 사용합니다.
              if (sharedPoolTotalAmount != null
                      && sharedPoolRemainingAmount != null
                      && sharedPoolTotalAmount >= 0L) {
                  sharedPoolUsedAmount = Math.max(0L, sharedPoolTotalAmount - sharedPoolRemainingAmount);
              }
          }

          // 11. 현재월 여부, 개인/공유 사용량, 표시 가능한 총량/잔량을 응답 DTO로 조립합니다.
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
        // 1. null 또는 yyyyMM로 볼 수 없는 범위는 즉시 거부합니다.
        if (yearMonth == null || yearMonth < 100001 || yearMonth > 999912) {
            throw new ApplicationException(DataErrorCode.INVALID_MONTH);
        }
        // 2. 뒤 두 자리가 실제 월 범위인지 확인합니다.
        int mm = yearMonth % 100;
        if (mm < 1 || mm > 12) {
            throw new ApplicationException(DataErrorCode.INVALID_MONTH);
        }
    }

    /**
     * DB 요약 응답의 표시 메타데이터는 유지하고, 잔량 필드는 Redis amount-only 조회 결과로 교체합니다.
     */
    private DataBalancesResDto applyTrafficCachedBalances(DataBalancesResDto response) {
        // 1. 회선 id가 없는 비정상 응답은 Redis 보정 없이 원본을 반환합니다.
        if (response == null || response.getLineId() == null || response.getLineId() <= 0) {
            return response;
        }

        // 2. 개인풀 잔량은 lineId 기준 Redis amount-only 조회 결과를 사용합니다.
        Long personalRemainingAmount =
                trafficRemainingBalanceQueryService.resolveIndividualActualRemaining(response.getLineId());
        // 3. 공유풀 잔량은 lineId로 familyId를 찾은 뒤 family 기준 Redis 잔량을 조회합니다.
        Long sharedRemainingAmount = familyLineMapper.findByLineId(response.getLineId())
                .map(FamilyLine::getFamilyId)
                .map(trafficRemainingBalanceQueryService::resolveSharedActualRemaining)
                .orElse(null);

        // 4. 이름/역할/요금제 같은 표시 메타데이터는 DB 응답에서 유지하고 잔량만 교체합니다.
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
        // 1. null과 음수는 사용량 합산에서 0으로 취급합니다.
        long normalizedBaseValue = baseValue == null ? 0L : Math.max(0L, baseValue);
        long normalizedAdditionalValue = Math.max(0L, additionalValue);
        // 2. long 범위를 넘는 합산은 최댓값으로 포화시켜 overflow를 피합니다.
        if (normalizedBaseValue > Long.MAX_VALUE - normalizedAdditionalValue) {
            return Long.MAX_VALUE;
        }
        // 3. 안전하게 정규화된 두 값을 더합니다.
        return normalizedBaseValue + normalizedAdditionalValue;
    }

    /**
     * Redis에 기록된 특정 회선의 일별 총 사용량 counter를 읽습니다.
     */
    private long readDailyTotalUsageFromRedis(long lineId, LocalDate usageDate) {
        // 1. lineId와 날짜로 일별 총 사용량 String counter key를 만듭니다.
        String key = trafficRedisKeyFactory.dailyTotalUsageKey(lineId, usageDate);
        try {
            // 2. Redis String 값을 읽고, 값이 없으면 아직 당일 사용량이 없는 것으로 봅니다.
            String rawValue = cacheStringRedisTemplate.opsForValue().get(key);
            if (rawValue == null) {
                return 0L;
            }

            // 3. 음수나 파싱 실패는 화면 보정에 쓰지 않도록 0 이상 값만 반환합니다.
            long parsed = Long.parseLong(rawValue);
            return Math.max(0L, parsed);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /**
     * Redis에 기록된 특정 회선의 월별 공유풀 사용량 hash field를 읽습니다.
     */
    private long readMonthlySharedUsageFromRedis(long lineId, YearMonth targetMonth) {
        // 1. lineId와 월로 월별 공유 사용량 Hash key를 만듭니다.
        String key = trafficRedisKeyFactory.monthlySharedUsageKey(lineId, targetMonth);
        try {
            // 2. Hash의 usage_amount field만 읽고, field가 없으면 월간 공유 사용량이 없는 것으로 봅니다.
            Object rawValue = cacheStringRedisTemplate.opsForHash().get(key, "usage_amount");
            if (rawValue == null) {
                return 0L;
            }

            // 3. 음수나 파싱 실패는 화면 보정에 쓰지 않도록 0 이상 값만 반환합니다.
            long parsed = Long.parseLong(String.valueOf(rawValue));
            return Math.max(0L, parsed);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /**
     * DB 집계 사용량의 null/음수 값을 표시 계산에 안전한 0 이상 값으로 변환합니다.
     */
    private long normalizeNonNegative(Long value) {
        // 1. null과 0 이하 값은 화면 사용량 계산에서 0으로 통일합니다.
        if (value == null || value <= 0L) {
            return 0L;
        }
        // 2. 양수 값은 원래 집계값을 그대로 사용합니다.
        return value;
    }

    /**
     * 총 제공량 표시값을 정규화하며, 음수는 무제한 sentinel `-1`로 유지합니다.
     */
    private Long normalizeTotalAmount(Long value) {
        // 1. 총량 자체가 없으면 표시값도 null로 유지합니다.
        if (value == null) {
            return null;
        }
        // 2. 음수는 무제한 sentinel로 해석해 -1로 통일합니다.
        if (value < 0L) {
            return -1L;
        }
        // 3. 0 이상 총량은 그대로 표시 가능한 값으로 사용합니다.
        return Math.max(0L, value);
    }

    /**
     * 잔량 표시값을 정규화하며, 음수는 무제한 sentinel `-1`로 유지합니다.
     */
    private Long normalizeRemainingAmount(Long value) {
        // 1. 잔량 자체가 없으면 표시값도 null로 유지합니다.
        if (value == null) {
            return null;
        }
        // 2. 음수는 무제한 sentinel로 해석해 -1로 통일합니다.
        if (value < 0L) {
            return -1L;
        }
        // 3. 0 이상 잔량은 그대로 표시 가능한 값으로 사용합니다.
        return Math.max(0L, value);
    }

}
