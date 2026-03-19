package com.pooli.family.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.family.domain.dto.mongo.SharedPoolTransferLog;
import com.pooli.family.domain.dto.request.UpdateSharedDataThresholdReqDto;
import com.pooli.family.domain.dto.response.FamilyMembersResDto;
import com.pooli.family.domain.dto.response.FamilyMembersSimpleResDto;
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FamilySharedPoolsService {

    private static final DateTimeFormatter HISTORY_EVENT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter YEAR_MONTH_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMM");

    private final FamilySharedPoolMapper sharedPoolMapper;
    private final FamilyMapper familyMapper;
    private final AlarmHistoryService alarmHistoryService;
    private final SharedPoolTransferLogRepository transferLogRepository;
    private final ObjectProvider<TrafficBalanceStateWriteThroughService> trafficBalanceStateWriteThroughServiceProvider;
    private final TrafficRemainingBalanceQueryService trafficRemainingBalanceQueryService;
    private final ObjectProvider<TrafficRedisKeyFactory> trafficRedisKeyFactoryProvider;
    private final ObjectProvider<TrafficRedisRuntimePolicy> trafficRedisRuntimePolicyProvider;
    private final ObjectProvider<TrafficQuotaCacheService> trafficQuotaCacheServiceProvider;

    public Long getFamilyIdByLineId(Long lineId) {
        Long familyId = sharedPoolMapper.selectFamilyIdByLineId(lineId);
        if (familyId == null) {
            throw new ApplicationException(SharedPoolErrorCode.NOT_FAMILY_MEMBER);
        }
        return familyId;
    }

    public SharedPoolMyStatusResDto getMySharedPoolStatus(Long lineId) {
        SharedPoolDomain domain = sharedPoolMapper.selectMySharedPoolStatus(lineId);
        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        Long actualRemainingData = trafficRemainingBalanceQueryService.resolveIndividualActualRemaining(
                lineId,
                domain.getRemainingData()
        );

        return SharedPoolMyStatusResDto.builder()
                .remainingData(actualRemainingData)
                .contributionAmount(domain.getPersonalContribution())
                .build();
    }

    public FamilySharedPoolResDto getFamilySharedPool(Long familyId) {
        SharedPoolDomain domain = sharedPoolMapper.selectFamilySharedPool(familyId);
        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        Long actualPoolRemainingData = trafficRemainingBalanceQueryService.resolveSharedActualRemaining(
                familyId,
                domain.getPoolRemainingData()
        );

        return FamilySharedPoolResDto.builder()
                .poolTotalData(domain.getPoolTotalData())
                .poolRemainingData(actualPoolRemainingData)
                .poolBaseData(domain.getPoolBaseData())
                .monthlyUsageAmount(domain.getMonthlyUsageAmount())
                .monthlyContributionAmount(domain.getMonthlyContributionAmount())
                .build();
    }

    @Transactional
    public void contributeToSharedPool(Long lineId, Long familyId, Long amount) {
        Long remainingData = sharedPoolMapper.selectRemainingData(lineId);
        boolean isUnlimited = remainingData != null && remainingData == -1L;

        Long currentMonthContribution = sharedPoolMapper.selectMonthlyContributionByFamilyId(
                lineId,
                LocalDate.now().withDayOfMonth(1),
                LocalDate.now().plusDays(1)
        );

        long maxSharedPool = 60L * 1024 * 1024 * 1024;
        if (currentMonthContribution + amount > maxSharedPool) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_LIMIT_EXCEEDED);
        }

        if (!isUnlimited) {
            if (remainingData == null) {
                throw new ApplicationException(SharedPoolErrorCode.LINE_NOT_FOUND);
            }
            if (remainingData < amount) {
                throw new ApplicationException(SharedPoolErrorCode.INSUFFICIENT_DATA);
            }
            sharedPoolMapper.updateLineRemainingData(lineId, amount);
        }

        sharedPoolMapper.updateFamilyPoolData(familyId, amount);
        syncSharedPoolWriteThroughAfterContribution(familyId, amount);
        sharedPoolMapper.insertContribution(familyId, lineId, amount);

        try {
            SharedPoolTransferLog logEntry = SharedPoolTransferLog.builder()
                    .familyId(familyId)
                    .lineId(lineId)
                    .amount(amount)
                    .createdAt(Instant.now())
                    .build();
            transferLogRepository.save(logEntry);
            log.info("shared pool contribution log saved lineId={} familyId={} amount={}", lineId, familyId, amount);
        } catch (Exception e) {
            log.error(
                    "shared pool contribution log save failed lineId={} familyId={} amount={}",
                    lineId,
                    familyId,
                    amount,
                    e
            );
        }

        sendAlarmToFamily(familyId, lineId, AlarmType.SHARED_POOL_CONTRIBUTION);
    }

    private void syncSharedPoolWriteThroughAfterContribution(Long familyId, Long amount) {
        if (familyId == null || familyId <= 0) {
            return;
        }

        TrafficBalanceStateWriteThroughService writeThroughService =
                trafficBalanceStateWriteThroughServiceProvider.getIfAvailable();
        if (writeThroughService == null) {
            log.debug("traffic_balance_state_write_through_unavailable familyId={}", familyId);
            return;
        }

        writeThroughService.markSharedBalanceNotEmpty(familyId);
        if (amount != null && amount > 0) {
            writeThroughService.markSharedMetaContribution(familyId, amount);
        }
    }

    public SharedPoolDetailResDto getSharedPoolDetail(Long familyId, Long lineId) {
        SharedPoolDomain domain = sharedPoolMapper.selectSharedPoolDetail(familyId, lineId);
        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        Long sharedDataLimit = sharedPoolMapper.selectSharedDataLimit(lineId);
        Long actualRemainingData = trafficRemainingBalanceQueryService.resolveIndividualActualRemaining(
                lineId,
                domain.getRemainingData()
        );
        Long actualPoolRemainingData = trafficRemainingBalanceQueryService.resolveSharedActualRemaining(
                familyId,
                domain.getPoolRemainingData()
        );

        return SharedPoolDetailResDto.builder()
                .basicDataAmount(domain.getBasicDataAmount())
                .remainingDataAmount(actualRemainingData)
                .sharedPoolTotalAmount(applySharedLimit(domain.getPoolTotalData(), sharedDataLimit))
                .sharedPoolRemainingAmount(applySharedLimit(actualPoolRemainingData, sharedDataLimit))
                .build();
    }

    public SharedPoolMainResDto getSharedPoolMain(Long familyId) {
        SharedPoolDomain domain = sharedPoolMapper.selectSharedPoolMain(familyId);
        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        Long monthlySharedPoolRemainingData = resolveFamilyMonthlySharedPoolRemaining(familyId);

        return SharedPoolMainResDto.builder()
                .sharedPoolBaseData(domain.getPoolBaseData())
                .sharedPoolAdditionalData(domain.getMonthlyContributionAmount())
                .sharedPoolRemainingData(monthlySharedPoolRemainingData)
                .sharedPoolTotalData(domain.getPoolTotalData())
                .build();
    }

    public List<SharedPoolHistoryItemResDto> getSharedPoolHistory(AuthUserDetails principal, Integer yearMonth) {
        YearMonth targetMonth = parseYearMonth(yearMonth);
        Long lineId = principal.getLineId();
        Long familyId = getFamilyIdByLineId(lineId);
        LocalDate startDate = targetMonth.atDay(1);
        LocalDate endDate = targetMonth.plusMonths(1).atDay(1);
        ZoneId zoneId = ZoneId.systemDefault();

        Map<Long, String> userNameByLineId = familyMapper.selectFamilyMembersSimpleByLineId(lineId).stream()
                .collect(Collectors.toMap(
                        FamilyMembersSimpleResDto::getLineId,
                        FamilyMembersSimpleResDto::getUserName,
                        (existingValue, replacementValue) -> existingValue
                ));

        List<SharedPoolHistoryItemResDto> usageHistory =
                sharedPoolMapper.selectSharedPoolUsageHistory(familyId, startDate, endDate);

        List<SharedPoolHistoryItemResDto> contributionHistory = transferLogRepository
                .findByFamilyIdAndCreatedAtBetween(
                        familyId,
                        startDate.atStartOfDay(zoneId).toInstant(),
                        endDate.atStartOfDay(zoneId).toInstant(),
                        Sort.by(Sort.Direction.DESC, "createdAt")
                )
                .stream()
                .map(log -> toContributionHistoryItem(log, userNameByLineId, zoneId))
                .toList();

        return Stream.concat(contributionHistory.stream(), usageHistory.stream())
                .sorted(Comparator.comparing(this::parseOccurredAtForSort).reversed())
                .toList();
    }

    public SharedDataThresholdResDto getSharedDataThreshold(Long familyId) {
        SharedPoolDomain domain = sharedPoolMapper.selectSharedDataThreshold(familyId);
        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        long poolTotal = domain.getPoolTotalData() != null ? domain.getPoolTotalData() : 0L;
        Long actualPoolRemainingData = trafficRemainingBalanceQueryService.resolveSharedActualRemaining(
                familyId,
                domain.getPoolRemainingData()
        );
        long poolRemaining = actualPoolRemainingData != null ? Math.max(0L, actualPoolRemainingData) : 0L;
        long usedData = Math.max(0L, poolTotal - poolRemaining);

        return SharedDataThresholdResDto.builder()
                .isThresholdActive(domain.getIsThresholdActive())
                .familyThreshold(domain.getFamilyThreshold())
                .minThreshold(usedData)
                .maxThreshold(poolTotal)
                .build();
    }

    public void updateSharedDataThreshold(Long familyId, UpdateSharedDataThresholdReqDto request) {
        sharedPoolMapper.updateSharedDataThreshold(familyId, request.getNewFamilyThreshold());
        syncSharedThresholdMetaAfterUpdate(familyId, request.getNewFamilyThreshold());
        sendAlarmToFamily(familyId, null, AlarmType.SHARED_POOL_THRESHOLD_CHANGE);
    }

    private void syncSharedThresholdMetaAfterUpdate(Long familyId, Long familyThreshold) {
        if (familyId == null || familyId <= 0) {
            return;
        }

        TrafficBalanceStateWriteThroughService writeThroughService =
                trafficBalanceStateWriteThroughServiceProvider.getIfAvailable();
        if (writeThroughService == null) {
            log.debug("traffic_balance_state_write_through_unavailable_for_threshold familyId={}", familyId);
            return;
        }

        long normalizedThreshold = familyThreshold == null ? 0L : Math.max(0L, familyThreshold);
        writeThroughService.markSharedMetaThresholdUpdated(familyId, normalizedThreshold, true);
    }

    public SharedPoolMonthlyUsageResDto getFamilyMonthlySharedUsageTotal(AuthUserDetails principal) {
        Long familyId = getFamilyIdByLineId(principal.getLineId());

        Long sharedPoolTotalData = sharedPoolMapper.selectFamilyPoolTotalData(familyId);
        if (sharedPoolTotalData == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        List<SharedPoolMonthlyUsageResDto.MemberUsageDto> membersUsageList =
                loadAdjustedFamilyMonthlySharedUsageByLine(familyId);

        return SharedPoolMonthlyUsageResDto.builder()
                .sharedPoolTotalData(sharedPoolTotalData)
                .membersUsageList(membersUsageList)
                .build();
    }

    public Long resolveFamilyMonthlySharedPoolRemaining(Long familyId) {
        Long sharedPoolTotalData = sharedPoolMapper.selectFamilyPoolTotalData(familyId);
        if (sharedPoolTotalData == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        long familyMonthlySharedUsageTotal = sumMonthlySharedUsage(loadAdjustedFamilyMonthlySharedUsageByLine(familyId));
        long normalizedTotalData = normalizeNonNegative(sharedPoolTotalData);
        return calculateRemaining(normalizedTotalData, familyMonthlySharedUsageTotal);
    }

    public FamilyMembersResDto.FamilyMemberDto resolveFamilyMemberMonthlySharedPoolDisplay(Long lineId) {
        Long familyId = getFamilyIdByLineId(lineId);
        Integer familyIdAsInt = Math.toIntExact(familyId);
        Long familySharedPoolTotal = sharedPoolMapper.selectFamilyPoolTotalData(familyId);
        Long familyMonthlySharedRemaining = resolveFamilyMonthlySharedPoolRemaining(familyId);
        Long familyMonthlySharedUsed = calculateFamilyMonthlySharedUsed(
                familySharedPoolTotal,
                familyMonthlySharedRemaining
        );

        return familyMapper.selectFamilyMembers(familyIdAsInt, lineId).stream()
                .filter(member -> member.getLineId() != null && Objects.equals(member.getLineId().longValue(), lineId))
                .findFirst()
                .map(member -> FamilyMembersResDto.FamilyMemberDto.builder()
                        .isMe(member.getIsMe())
                        .userId(member.getUserId())
                        .lineId(member.getLineId())
                        .planId(member.getPlanId())
                        .userName(member.getUserName())
                        .phone(member.getPhone())
                        .planName(member.getPlanName())
                        .remainingData(member.getRemainingData())
                        .basicDataAmount(member.getBasicDataAmount())
                        .role(member.getRole())
                        .sharedPoolTotalAmount(member.getSharedPoolTotalAmount())
                        .sharedPoolRemainingAmount(resolveSharedPoolRemainingAmount(
                                member,
                                familyMonthlySharedRemaining,
                                familyMonthlySharedUsed
                        ))
                        .sharedLimitActive(member.getSharedLimitActive())
                        .build())
                .orElseThrow(() -> new ApplicationException(SharedPoolErrorCode.NOT_FAMILY_MEMBER));
    }

    public Long resolveSharedPoolRemainingAmount(
            FamilyMembersResDto.FamilyMemberDto member,
            Long familyMonthlySharedRemaining,
            Long familyMonthlySharedUsed
    ) {
        if (familyMonthlySharedRemaining == null) {
            return member.getSharedPoolRemainingAmount();
        }
        if (!Boolean.TRUE.equals(member.getSharedLimitActive())) {
            return familyMonthlySharedRemaining;
        }

        Long policyTotalAmount = member.getSharedPoolTotalAmount();
        if (policyTotalAmount == null) {
            return member.getSharedPoolRemainingAmount();
        }
        if (policyTotalAmount == -1L) {
            return familyMonthlySharedRemaining;
        }

        long normalizedFamilyRemaining = Math.max(0L, familyMonthlySharedRemaining);
        long normalizedFamilyUsed = familyMonthlySharedUsed == null ? 0L : Math.max(0L, familyMonthlySharedUsed);
        long policyRemaining = Math.max(0L, policyTotalAmount - normalizedFamilyUsed);
        return Math.min(normalizedFamilyRemaining, policyRemaining);
    }

    public Long calculateFamilyMonthlySharedUsed(Long familySharedPoolTotal, Long familyMonthlySharedRemaining) {
        if (familySharedPoolTotal == null || familyMonthlySharedRemaining == null) {
            return null;
        }

        long normalizedTotal = Math.max(0L, familySharedPoolTotal);
        long normalizedRemaining = Math.max(0L, familyMonthlySharedRemaining);
        return Math.max(0L, normalizedTotal - normalizedRemaining);
    }

    private Long applySharedLimit(Long actualAmount, Long sharedDataLimit) {
        if (actualAmount == null) {
            return null;
        }
        if (sharedDataLimit == null || sharedDataLimit == -1L) {
            return actualAmount;
        }
        if (actualAmount == -1L) {
            return sharedDataLimit;
        }
        return Math.min(Math.max(0L, actualAmount), Math.max(0L, sharedDataLimit));
    }

    private SharedPoolMonthlyUsageResDto.MemberUsageDto adjustMonthlySharedUsage(
            SharedPoolMonthlyUsageResDto.MemberUsageDto memberUsage
    ) {
        long dbUsage = normalizeNonNegative(memberUsage.getMonthlySharedPoolUsage());
        long redisUsage = readMonthlySharedUsageFromRedis(memberUsage.getLineId());
        long adjustedUsage = Math.max(dbUsage, redisUsage);

        return SharedPoolMonthlyUsageResDto.MemberUsageDto.builder()
                .userName(memberUsage.getUserName())
                .phoneNumber(memberUsage.getPhoneNumber())
                .monthlySharedPoolUsage(adjustedUsage)
                .build();
    }

    private List<SharedPoolMonthlyUsageResDto.MemberUsageDto> loadAdjustedFamilyMonthlySharedUsageByLine(Long familyId) {
        return sharedPoolMapper
                .selectFamilyMonthlySharedUsageByLine(familyId)
                .stream()
                .map(this::adjustMonthlySharedUsage)
                .toList();
    }

    private long readMonthlySharedUsageFromRedis(Long lineId) {
        if (lineId == null || lineId <= 0) {
            return 0L;
        }

        TrafficRedisKeyFactory trafficRedisKeyFactory = trafficRedisKeyFactoryProvider.getIfAvailable();
        TrafficRedisRuntimePolicy trafficRedisRuntimePolicy = trafficRedisRuntimePolicyProvider.getIfAvailable();
        TrafficQuotaCacheService trafficQuotaCacheService = trafficQuotaCacheServiceProvider.getIfAvailable();
        if (trafficRedisKeyFactory == null
                || trafficRedisRuntimePolicy == null
                || trafficQuotaCacheService == null) {
            return 0L;
        }

        YearMonth targetMonth = YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        String monthlySharedUsageKey = trafficRedisKeyFactory.monthlySharedUsageKey(lineId, targetMonth);
        try {
            return Math.max(0L, trafficQuotaCacheService.readValueOrDefault(monthlySharedUsageKey, 0L));
        } catch (RuntimeException e) {
            log.warn("family_shared_monthly_usage_redis_read_failed lineId={} key={}", lineId, monthlySharedUsageKey, e);
            return 0L;
        }
    }

    private long normalizeNonNegative(Long value) {
        if (value == null) {
            return 0L;
        }
        return Math.max(0L, value);
    }

    private long sumMonthlySharedUsage(List<SharedPoolMonthlyUsageResDto.MemberUsageDto> membersUsageList) {
        long total = 0L;
        for (SharedPoolMonthlyUsageResDto.MemberUsageDto memberUsage : membersUsageList) {
            total = safeAdd(total, normalizeNonNegative(memberUsage.getMonthlySharedPoolUsage()));
        }
        return total;
    }

    private long calculateRemaining(long total, long used) {
        if (used >= total) {
            return 0L;
        }
        return total - used;
    }

    private long safeAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private YearMonth parseYearMonth(Integer yearMonth) {
        if (yearMonth == null) {
            throw new ApplicationException(
                    CommonErrorCode.INVALID_REQUEST_PARAM,
                    "yearMonth\uB294 yyyyMM \uD615\uC2DD\uC774\uC5B4\uC57C \uD569\uB2C8\uB2E4"
            );
        }

        String value = String.valueOf(yearMonth);
        if (value.length() != 6) {
            throw new ApplicationException(
                    CommonErrorCode.INVALID_REQUEST_PARAM,
                    "yearMonth\uB294 yyyyMM \uD615\uC2DD\uC774\uC5B4\uC57C \uD569\uB2C8\uB2E4"
            );
        }

        try {
            return YearMonth.parse(value, YEAR_MONTH_FORMATTER);
        } catch (DateTimeParseException exception) {
            throw new ApplicationException(
                    CommonErrorCode.INVALID_REQUEST_PARAM,
                    "yearMonth\uB294 yyyyMM \uD615\uC2DD\uC774\uC5B4\uC57C \uD569\uB2C8\uB2E4"
            );
        }
    }

    private SharedPoolHistoryItemResDto toContributionHistoryItem(
            SharedPoolTransferLog log,
            Map<Long, String> userNameByLineId,
            ZoneId zoneId
    ) {
        return SharedPoolHistoryItemResDto.builder()
                .eventType("CONTRIBUTION")
                .title("\uB370\uC774\uD130 \uBCF4\uD0DC\uAE30")
                .userName(userNameByLineId.getOrDefault(log.getLineId(), "\uC54C \uC218 \uC5C6\uC74C"))
                .occurredAt(log.getCreatedAt().atZone(zoneId).toLocalDateTime().format(HISTORY_EVENT_FORMATTER))
                .amount(log.getAmount())
                .precision("EVENT")
                .build();
    }

    private LocalDateTime parseOccurredAtForSort(SharedPoolHistoryItemResDto item) {
        if ("DAY".equals(item.getPrecision())) {
            return LocalDate.parse(item.getOccurredAt()).atStartOfDay();
        }

        return LocalDateTime.parse(item.getOccurredAt(), HISTORY_EVENT_FORMATTER);
    }

    private void sendAlarmToFamily(Long familyId, Long excludeLineId, AlarmType alarmType) {
        List<Long> familyLineIds = sharedPoolMapper.selectLineIdsByFamilyId(familyId);
        for (Long targetLineId : familyLineIds) {
            if (excludeLineId != null && targetLineId.equals(excludeLineId)) {
                continue;
            }
            alarmHistoryService.createAlarm(targetLineId, AlarmCode.FAMILY, alarmType);
        }
    }
}
