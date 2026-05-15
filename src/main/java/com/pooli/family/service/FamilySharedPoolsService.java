package com.pooli.family.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
import com.pooli.traffic.service.runtime.TrafficRemainingBalanceCacheService;
import com.pooli.traffic.service.runtime.TrafficRemainingBalanceQueryService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 가족 공유풀 화면/기여/임계치 기능을 처리하는 애플리케이션 서비스입니다.
 *
 * <p>Family DB를 기준으로 공유풀 메타데이터와 가족 구성원 정보를 조회하고,
 * Redis-Only 트래픽 차감 구조에서 화면에 필요한 실시간 잔량/사용량은 Traffic runtime 서비스를 통해 보정합니다.
 * MongoDB 기여 이력과 가족 알림 전파도 이 서비스의 orchestration 책임에 포함됩니다.
 */
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
    private final ObjectProvider<TrafficRemainingBalanceCacheService> trafficRemainingBalanceCacheServiceProvider;

    /**
     * 회선이 속한 가족 식별자를 조회합니다.
     *
     * <p>가족 소속이 없는 회선은 공유풀 기능을 사용할 수 없으므로 도메인 예외로 중단합니다.
     */
    public Long getFamilyIdByLineId(Long lineId) {
        // Family DB에서 lineId 기준 가족 소속을 확인합니다.
        Long familyId = sharedPoolMapper.selectFamilyIdByLineId(lineId);
        if (familyId == null) {
            throw new ApplicationException(SharedPoolErrorCode.NOT_FAMILY_MEMBER);
        }
        return familyId;
    }

    /**
     * 내 공유풀 상태 화면에 필요한 개인 잔량과 월 기여량을 반환합니다.
     *
     * <p>실제 표시 잔량은 Traffic Redis amount-only 조회 결과만 사용합니다.
     */
    public SharedPoolMyStatusResDto getMySharedPoolStatus(Long lineId) {
        // 공유풀 가입 상태와 DB 기준 개인 잔량/기여량을 조회합니다.
        SharedPoolDomain domain = sharedPoolMapper.selectMySharedPoolStatus(lineId);
        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        // 실시간 차감은 Redis에서 발생하므로 화면 잔량은 Traffic 조회 서비스로 보정합니다.
        Long actualRemainingData = trafficRemainingBalanceQueryService.resolveIndividualActualRemaining(
                lineId
        );

        return SharedPoolMyStatusResDto.builder()
                .remainingData(actualRemainingData)
                .contributionAmount(domain.getPersonalContribution())
                .build();
    }

    /**
     * 가족 공유풀 요약 정보를 반환합니다.
     *
     * <p>공유풀 총량/기본량/월 집계는 DB 값을 사용하고, 공유풀 잔량은 Redis 실시간 잔량만 사용합니다.
     */
    public FamilySharedPoolResDto getFamilySharedPool(Long familyId) {
        // 가족 공유풀 메타데이터를 조회합니다.
        SharedPoolDomain domain = sharedPoolMapper.selectFamilySharedPool(familyId);
        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        // Redis-Only 차감 이후 공유풀 잔량은 Redis amount-only 조회 결과만 사용합니다.
        Long actualPoolRemainingData = trafficRemainingBalanceQueryService.resolveSharedActualRemaining(
                familyId
        );

        return FamilySharedPoolResDto.builder()
                .poolTotalData(domain.getPoolTotalData())
                .poolRemainingData(actualPoolRemainingData)
                .poolBaseData(domain.getPoolBaseData())
                .monthlyUsageAmount(domain.getMonthlyUsageAmount())
                .monthlyContributionAmount(domain.getMonthlyContributionAmount())
                .build();
    }

    /**
     * 개인 회선의 데이터를 가족 공유풀에 기여합니다.
     *
     * <p>개인 잔량 검증, 공유풀 총량 증가, 기여 이력 저장, Traffic Redis balance write-through,
     * 가족 알림 발송을 하나의 트랜잭션 흐름에서 조율합니다. Mongo 로그 저장과 알림은 보조 기능입니다.
     */
    @Transactional
    public void contributeToSharedPool(Long lineId, Long familyId, Long amount) {
        // 무제한 회선(-1)은 개인 잔량 차감 없이 공유풀 기여를 허용합니다.
        Long remainingData = sharedPoolMapper.selectRemainingData(lineId);
        boolean isUnlimited = remainingData != null && remainingData == -1L;

        // 월별 가족 공유풀 기여 상한을 검증하기 위해 현재월 누적 기여량을 조회합니다.
        Long currentMonthContribution = sharedPoolMapper.selectMonthlyContributionByFamilyId(
                lineId,
                LocalDate.now().withDayOfMonth(1),
                LocalDate.now().plusDays(1)
        );

        long maxSharedPool = 60L * 1024 * 1024 * 1024;
        if (currentMonthContribution + amount > maxSharedPool) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_LIMIT_EXCEEDED);
        }

        // 유한 요금제 회선은 존재 여부와 잔량 충분 여부를 확인한 뒤 개인 잔량을 차감합니다.
        if (!isUnlimited) {
            if (remainingData == null) {
                throw new ApplicationException(SharedPoolErrorCode.LINE_NOT_FOUND);
            }
            if (remainingData < amount) {
                throw new ApplicationException(SharedPoolErrorCode.INSUFFICIENT_DATA);
            }
            sharedPoolMapper.updateLineRemainingData(lineId, amount);
        }

        // Family DB의 공유풀 총량과 기여 이력을 영속화합니다.
        sharedPoolMapper.updateFamilyPoolData(familyId, amount);
        syncSharedPoolWriteThroughAfterContribution(lineId, familyId, amount, isUnlimited);
        sharedPoolMapper.insertContribution(familyId, lineId, amount);

        try {
            // MongoDB 이력은 상세 히스토리 화면용 보조 저장소입니다.
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

        // 기여자를 제외한 가족 구성원에게 공유풀 기여 알림을 전파합니다.
        sendAlarmToFamily(familyId, lineId, AlarmType.SHARED_POOL_CONTRIBUTION);
    }

    /**
     * 공유풀 기여 성공 후 Traffic Redis balance와 family meta cache에 증가분을 반영합니다.
     *
     * <p>Traffic bean은 profile에 따라 없을 수 있으므로 선택적으로 조회합니다.
     */
    private void syncSharedPoolWriteThroughAfterContribution(
            Long lineId,
            Long familyId,
            Long amount,
            boolean individualUnlimited
    ) {
        if (lineId == null || lineId <= 0 || familyId == null || familyId <= 0) {
            return;
        }

        // Traffic 캐시 write-through는 local/traffic/api profile에서만 제공되는 보조 기능입니다.
        // Family 기여 트랜잭션은 해당 bean이 없는 실행 환경에서도 성공해야 하므로 선택적으로 조회합니다.
        TrafficBalanceStateWriteThroughService writeThroughService =
                trafficBalanceStateWriteThroughServiceProvider.getIfAvailable();
        if (writeThroughService == null) {
            log.debug("traffic_balance_state_write_through_unavailable familyId={}", familyId);
            return;
        }

        if (amount != null && amount > 0) {
            writeThroughService.markSharedPoolContribution(lineId, familyId, amount, individualUnlimited);
        }
    }

    /**
     * 공유풀 상세 화면에 필요한 개인 잔량, 공유풀 총량, 공유풀 잔량을 반환합니다.
     *
     * <p>개인/공유 잔량은 Redis 실시간 값으로 보정하고, 회선별 공유 제한이 있으면 표시값에 제한을 적용합니다.
     */
    public SharedPoolDetailResDto getSharedPoolDetail(Long familyId, Long lineId) {
        // 상세 화면에 필요한 기본 DB 스냅샷을 조회합니다.
        SharedPoolDomain domain = sharedPoolMapper.selectSharedPoolDetail(familyId, lineId);
        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        // 회선별 공유 한도와 Redis 기준 실제 잔량을 함께 조회해 표시값을 계산합니다.
        Long sharedDataLimit = sharedPoolMapper.selectSharedDataLimit(lineId);
        Long actualRemainingData = trafficRemainingBalanceQueryService.resolveIndividualActualRemaining(
                lineId
        );
        Long actualPoolRemainingData = trafficRemainingBalanceQueryService.resolveSharedActualRemaining(
                familyId
        );

        return SharedPoolDetailResDto.builder()
                .basicDataAmount(domain.getBasicDataAmount())
                .remainingDataAmount(actualRemainingData)
                .sharedPoolTotalAmount(applySharedLimit(domain.getPoolTotalData(), sharedDataLimit))
                .sharedPoolRemainingAmount(applySharedLimit(actualPoolRemainingData, sharedDataLimit))
                .build();
    }

    /**
     * 공유풀 메인 화면의 가족 공유풀 기본량, 추가량, 잔량, 총량을 반환합니다.
     */
    public SharedPoolMainResDto getSharedPoolMain(Long familyId) {
        // DB 기준 공유풀 메인 스냅샷을 조회합니다.
        SharedPoolDomain domain = sharedPoolMapper.selectSharedPoolMain(familyId);
        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        // 공유풀 잔량은 Redis 실시간 차감 상태를 반영해 보정합니다.
        Long actualPoolRemainingData = trafficRemainingBalanceQueryService.resolveSharedActualRemaining(
                familyId
        );

        return SharedPoolMainResDto.builder()
                .sharedPoolBaseData(domain.getPoolBaseData())
                .sharedPoolAdditionalData(domain.getMonthlyContributionAmount())
                .sharedPoolRemainingData(actualPoolRemainingData)
                .sharedPoolTotalData(domain.getPoolTotalData())
                .build();
    }

    /**
     * 지정 월의 공유풀 사용/기여 이력을 시간 역순으로 반환합니다.
     *
     * <p>사용 이력은 RDB 일자 집계에서, 기여 이력은 MongoDB 이벤트 로그에서 조회한 뒤 하나의 목록으로 병합합니다.
     */
    public List<SharedPoolHistoryItemResDto> getSharedPoolHistory(AuthUserDetails principal, Integer yearMonth) {
        // 요청 월과 현재 사용자의 가족 식별자를 확정합니다.
        YearMonth targetMonth = parseYearMonth(yearMonth);
        Long lineId = principal.getLineId();
        Long familyId = getFamilyIdByLineId(lineId);
        LocalDate startDate = targetMonth.atDay(1);
        LocalDate endDate = targetMonth.plusMonths(1).atDay(1);
        ZoneId zoneId = ZoneId.systemDefault();

        // Mongo 기여 로그에는 이름이 없으므로 lineId -> userName 매핑을 미리 구성합니다.
        Map<Long, String> userNameByLineId = familyMapper.selectFamilyMembersSimpleByLineId(lineId).stream()
                .collect(Collectors.toMap(
                        FamilyMembersSimpleResDto::getLineId,
                        FamilyMembersSimpleResDto::getUserName,
                        (existingValue, replacementValue) -> existingValue
                ));

        // RDB 사용 이력과 Mongo 기여 이력을 같은 DTO로 변환해 병합합니다.
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

    /**
     * 공유풀 임계치 설정 화면에 필요한 활성 여부, 현재 임계치, 허용 범위를 반환합니다.
     *
     * <p>최소 임계치는 실사용량 이상으로 제한하기 위해 공유풀 총량과 Redis 기준 실제 잔량으로 계산합니다.
     */
    public SharedDataThresholdResDto getSharedDataThreshold(Long familyId) {
        // 임계치 정책과 공유풀 총량/잔량 스냅샷을 조회합니다.
        SharedPoolDomain domain = sharedPoolMapper.selectSharedDataThreshold(familyId);
        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        // 이미 사용한 데이터보다 낮은 임계치를 설정하지 않도록 실사용량을 산출합니다.
        long poolTotal = domain.getPoolTotalData() != null ? domain.getPoolTotalData() : 0L;
        Long actualPoolRemainingData = trafficRemainingBalanceQueryService.resolveSharedActualRemaining(
                familyId
        );
        Long usedData = actualPoolRemainingData == null
                ? null
                : Math.max(0L, poolTotal - Math.max(0L, actualPoolRemainingData));

        return SharedDataThresholdResDto.builder()
                .isThresholdActive(domain.getIsThresholdActive())
                .familyThreshold(domain.getFamilyThreshold())
                .minThreshold(usedData)
                .maxThreshold(poolTotal)
                .build();
    }

    /**
     * 가족 공유풀 임계치를 변경하고 가족 구성원에게 변경 알림을 보냅니다.
     */
    public void updateSharedDataThreshold(Long familyId, UpdateSharedDataThresholdReqDto request) {
        // Family DB의 임계치를 먼저 갱신합니다.
        sharedPoolMapper.updateSharedDataThreshold(familyId, request.getNewFamilyThreshold());
        // Traffic family meta cache가 있으면 변경된 임계치를 write-through합니다.
        syncSharedThresholdMetaAfterUpdate(familyId, request.getNewFamilyThreshold());
        // 임계치 변경은 특정 기여자가 없으므로 가족 전체에 알림을 전파합니다.
        sendAlarmToFamily(familyId, null, AlarmType.SHARED_POOL_THRESHOLD_CHANGE);
    }

    /**
     * 공유풀 임계치 변경 후 Traffic family meta cache의 임계치 필드를 갱신합니다.
     */
    private void syncSharedThresholdMetaAfterUpdate(Long familyId, Long familyThreshold) {
        if (familyId == null || familyId <= 0) {
            return;
        }

        // 임계치 변경의 영속 처리는 Family DB update가 기준이며, Traffic 캐시 반영은 가능한 경우에만 수행합니다.
        TrafficBalanceStateWriteThroughService writeThroughService =
                trafficBalanceStateWriteThroughServiceProvider.getIfAvailable();
        if (writeThroughService == null) {
            log.debug("traffic_balance_state_write_through_unavailable_for_threshold familyId={}", familyId);
            return;
        }

        long normalizedThreshold = familyThreshold == null ? 0L : Math.max(0L, familyThreshold);
        writeThroughService.markSharedMetaThresholdUpdated(familyId, normalizedThreshold, true);
    }

    /**
     * 가족 구성원별 월간 공유풀 사용량 목록을 반환합니다.
     *
     * <p>RDB 집계와 Redis 월간 공유 사용량을 함께 고려하고, 가족 전체 실제 사용량과 구성원 합계가 맞도록 보정합니다.
     */
    public SharedPoolMonthlyUsageResDto getFamilyMonthlySharedUsageTotal(AuthUserDetails principal) {
        // 요청자의 가족과 공유풀 총량을 확정합니다.
        Long familyId = getFamilyIdByLineId(principal.getLineId());

        Long sharedPoolTotalData = sharedPoolMapper.selectFamilyPoolTotalData(familyId);
        if (sharedPoolTotalData == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        // 가족 전체 실제 사용량은 총량 - Redis 기준 실제 잔량으로 계산합니다.
        Long familyActualSharedRemaining = resolveFamilyActualSharedRemaining(familyId);
        Long familyActualSharedUsed = calculateFamilyActualSharedUsed(sharedPoolTotalData, familyActualSharedRemaining);

        // 구성원별 사용량은 RDB/Redis 중 더 큰 값을 사용한 뒤 가족 전체 사용량과 합계를 맞춥니다.
        List<SharedPoolMonthlyUsageResDto.MemberUsageDto> membersUsageList =
                loadAdjustedFamilyMonthlySharedUsageByLine(familyId);
        membersUsageList = reconcileMemberActualSharedUsage(membersUsageList, familyActualSharedUsed);

        return SharedPoolMonthlyUsageResDto.builder()
                .sharedPoolTotalData(sharedPoolTotalData)
                .membersUsageList(membersUsageList)
                .build();
    }

    /**
     * 가족 공유풀의 실제 잔량을 Redis amount-only 기준으로 반환합니다.
     */
    public Long resolveFamilyActualSharedRemaining(Long familyId) {
        // 가족 존재 여부만 확인하고, 잔량은 DB fallback 없이 Traffic 조회 서비스 결과를 그대로 사용합니다.
        SharedPoolDomain domain = sharedPoolMapper.selectSharedPoolMain(familyId);
        if (domain == null) {
            throw new ApplicationException(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND);
        }

        return trafficRemainingBalanceQueryService.resolveSharedActualRemaining(
                familyId
        );
    }

    /**
     * 가족 공유풀 총량과 실제 잔량으로 실제 사용량을 계산합니다.
     */
    public Long calculateFamilyActualSharedUsed(Long familySharedPoolTotal, Long familyActualSharedRemaining) {
        // 둘 중 하나라도 없으면 DB fallback 없이 unavailable을 전파합니다.
        if (familySharedPoolTotal == null || familyActualSharedRemaining == null) {
            return null;
        }

        // 음수 입력은 방어적으로 0으로 보정하고 사용량도 0 미만이 되지 않게 합니다.
        long normalizedTotal = Math.max(0L, familySharedPoolTotal);
        long normalizedRemaining = Math.max(0L, familyActualSharedRemaining);
        return Math.max(0L, normalizedTotal - normalizedRemaining);
    }

    /**
     * 가족 구성원 목록에서 현재 회선의 표시용 잔량/공유풀 값을 실제 Redis 상태 기준으로 보정합니다.
     */
    public FamilyMembersResDto.FamilyMemberDto resolveFamilyMemberActualDisplay(Long lineId) {
        // 현재 회선의 가족과 가족 공유풀 실제 사용량을 먼저 계산합니다.
        Long familyId = getFamilyIdByLineId(lineId);
        Integer familyIdAsInt = Math.toIntExact(familyId);
        Long familySharedPoolTotal = sharedPoolMapper.selectFamilyPoolTotalData(familyId);
        Long familyActualSharedRemaining = resolveFamilyActualSharedRemaining(familyId);
        Long familyActualSharedUsed = calculateFamilyActualSharedUsed(
                familySharedPoolTotal,
                familyActualSharedRemaining
        );

        // 가족 구성원 목록 중 현재 회선만 찾아 개인 잔량과 공유풀 표시 잔량을 보정합니다.
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
                        .remainingData(trafficRemainingBalanceQueryService.resolveIndividualActualRemaining(
                                lineId
                        ))
                        .basicDataAmount(member.getBasicDataAmount())
                        .role(member.getRole())
                        .sharedPoolTotalAmount(member.getSharedPoolTotalAmount())
                        .sharedPoolRemainingAmount(resolveDisplaySharedPoolRemainingAmount(
                                member,
                                familyActualSharedRemaining,
                                familyActualSharedUsed
                        ))
                        .sharedLimitActive(member.getSharedLimitActive())
                        .build())
                .orElseThrow(() -> new ApplicationException(SharedPoolErrorCode.NOT_FAMILY_MEMBER));
    }

    /**
     * 회선별 공유 제한 정책을 반영한 표시용 공유풀 잔량을 계산합니다.
     */
    public Long resolveDisplaySharedPoolRemainingAmount(
            FamilyMembersResDto.FamilyMemberDto member,
            Long familyActualSharedRemaining,
            Long familyActualSharedUsed
    ) {
        // Redis 실제 잔량을 모르면 DB fallback 없이 unavailable을 표시합니다.
        if (familyActualSharedRemaining == null) {
            return null;
        }
        // 회선별 제한이 비활성화된 경우 가족 전체 실제 잔량을 그대로 보여줍니다.
        if (!Boolean.TRUE.equals(member.getSharedLimitActive())) {
            return familyActualSharedRemaining;
        }

        // 제한 정책 총량이 없으면 DB fallback 없이 unavailable을 표시합니다.
        Long policyTotalAmount = member.getSharedPoolTotalAmount();
        if (policyTotalAmount == null) {
            return null;
        }
        if (policyTotalAmount == -1L) {
            return familyActualSharedRemaining;
        }

        // 제한 총량에서 가족 실제 사용량을 뺀 회선별 정책 잔량과 가족 전체 잔량 중 작은 값을 표시합니다.
        long normalizedFamilyRemaining = Math.max(0L, familyActualSharedRemaining);
        long normalizedFamilyUsed = familyActualSharedUsed == null ? 0L : Math.max(0L, familyActualSharedUsed);
        long policyRemaining = Math.max(0L, policyTotalAmount - normalizedFamilyUsed);
        return Math.min(normalizedFamilyRemaining, policyRemaining);
    }

    /**
     * 실제 잔량에 회선별 공유 데이터 한도를 적용해 표시 가능한 공유풀 값을 계산합니다.
     */
    private Long applySharedLimit(Long actualAmount, Long sharedDataLimit) {
        // 실제 잔량이 없으면 호출자가 기존 표시 정책을 유지할 수 있게 null을 반환합니다.
        if (actualAmount == null) {
            return null;
        }
        // 제한이 없거나 무제한이면 실제 잔량을 그대로 사용합니다.
        if (sharedDataLimit == null || sharedDataLimit == -1L) {
            return actualAmount;
        }
        // 실제 잔량이 무제한이면 제한값이 표시 가능한 상한입니다.
        if (actualAmount == -1L) {
            return sharedDataLimit;
        }
        // 일반 케이스는 실제 잔량과 제한값을 0 이상으로 보정한 뒤 작은 값을 사용합니다.
        return Math.min(Math.max(0L, actualAmount), Math.max(0L, sharedDataLimit));
    }

    /**
     * 구성원별 월간 공유풀 사용량을 Redis 실시간 월간 사용량으로 보정합니다.
     */
    private SharedPoolMonthlyUsageResDto.MemberUsageDto adjustMonthlySharedUsage(
            SharedPoolMonthlyUsageResDto.MemberUsageDto memberUsage
    ) {
        // Redis는 실시간 차감 경로의 최신 사용량을 담으므로 DB 집계보다 큰 경우 화면값으로 사용합니다.
        long dbUsage = normalizeNonNegative(memberUsage.getMonthlySharedPoolUsage());
        long redisUsage = readMonthlySharedUsageFromRedis(memberUsage.getLineId());
        long adjustedUsage = Math.max(dbUsage, redisUsage);

        return SharedPoolMonthlyUsageResDto.MemberUsageDto.builder()
                .userName(memberUsage.getUserName())
                .phoneNumber(memberUsage.getPhoneNumber())
                .monthlySharedPoolUsage(adjustedUsage)
                .build();
    }

    /**
     * 구성원별 사용량 합계가 가족 전체 실제 공유풀 사용량과 맞도록 보정합니다.
     */
    private List<SharedPoolMonthlyUsageResDto.MemberUsageDto> reconcileMemberActualSharedUsage(
            List<SharedPoolMonthlyUsageResDto.MemberUsageDto> membersUsageList,
            Long familyActualSharedUsed
    ) {
        // 보정 기준이 없으면 원본 목록을 그대로 반환합니다.
        if (membersUsageList == null || membersUsageList.isEmpty() || familyActualSharedUsed == null) {
            return membersUsageList;
        }

        // 현재 구성원 합계와 가족 전체 실제 사용량의 차이를 계산합니다.
        long targetUsed = Math.max(0L, familyActualSharedUsed);
        long currentUsed = membersUsageList.stream()
                .map(SharedPoolMonthlyUsageResDto.MemberUsageDto::getMonthlySharedPoolUsage)
                .mapToLong(this::normalizeNonNegative)
                .sum();

        if (currentUsed == targetUsed) {
            return membersUsageList;
        }
        // 가족 전체 사용량이 0이면 모든 구성원 표시 사용량도 0으로 맞춥니다.
        if (targetUsed == 0L) {
            return membersUsageList.stream()
                    .map(memberUsage -> SharedPoolMonthlyUsageResDto.MemberUsageDto.builder()
                            .userName(memberUsage.getUserName())
                            .phoneNumber(memberUsage.getPhoneNumber())
                            .monthlySharedPoolUsage(0L)
                            .build())
                    .toList();
        }
        // 구성원별 근거가 전혀 없으면 임의 배분하지 않고 원본을 유지합니다.
        if (currentUsed == 0L) {
            log.warn("family_shared_usage_reconcile_skipped_no_member_usage familyUsed={}", targetUsed);
            return membersUsageList;
        }

        List<SharedPoolMonthlyUsageResDto.MemberUsageDto> adjustedList = new ArrayList<>(membersUsageList);
        long difference = targetUsed - currentUsed;

        // 가족 전체 사용량이 구성원 합계보다 크면 가장 많이 사용한 구성원에게 차이를 더합니다.
        if (difference > 0L) {
            int targetIndex = 0;
            long maxUsage = Long.MIN_VALUE;
            for (int index = 0; index < adjustedList.size(); index++) {
                long usage = normalizeNonNegative(adjustedList.get(index).getMonthlySharedPoolUsage());
                if (usage > maxUsage) {
                    maxUsage = usage;
                    targetIndex = index;
                }
            }

            SharedPoolMonthlyUsageResDto.MemberUsageDto targetMember = adjustedList.get(targetIndex);
            adjustedList.set(
                    targetIndex,
                    SharedPoolMonthlyUsageResDto.MemberUsageDto.builder()
                            .userName(targetMember.getUserName())
                            .phoneNumber(targetMember.getPhoneNumber())
                            .monthlySharedPoolUsage(
                                    normalizeNonNegative(targetMember.getMonthlySharedPoolUsage()) + difference
                            )
                            .build()
            );
            return adjustedList;
        }

        // 가족 전체 사용량이 구성원 합계보다 작으면 사용량이 큰 구성원부터 차이를 차감합니다.
        long remainingToSubtract = Math.abs(difference);
        while (remainingToSubtract > 0L) {
            int targetIndex = -1;
            long maxUsage = 0L;
            for (int index = 0; index < adjustedList.size(); index++) {
                long usage = normalizeNonNegative(adjustedList.get(index).getMonthlySharedPoolUsage());
                if (usage > maxUsage) {
                    maxUsage = usage;
                    targetIndex = index;
                }
            }

            if (targetIndex < 0 || maxUsage == 0L) {
                break;
            }

            long deduction = Math.min(maxUsage, remainingToSubtract);
            SharedPoolMonthlyUsageResDto.MemberUsageDto targetMember = adjustedList.get(targetIndex);
            adjustedList.set(
                    targetIndex,
                    SharedPoolMonthlyUsageResDto.MemberUsageDto.builder()
                            .userName(targetMember.getUserName())
                            .phoneNumber(targetMember.getPhoneNumber())
                            .monthlySharedPoolUsage(maxUsage - deduction)
                            .build()
            );
            remainingToSubtract -= deduction;
        }

        return adjustedList;
    }

    /**
     * 가족 구성원별 월간 공유풀 사용량을 조회하고 Redis 실시간 사용량으로 보정합니다.
     */
    private List<SharedPoolMonthlyUsageResDto.MemberUsageDto> loadAdjustedFamilyMonthlySharedUsageByLine(Long familyId) {
        return sharedPoolMapper
                .selectFamilyMonthlySharedUsageByLine(familyId)
                .stream()
                .map(this::adjustMonthlySharedUsage)
                .toList();
    }

    /**
     * Redis의 현재월 공유풀 사용량 값을 읽어 화면 보정용 값으로 반환합니다.
     */
    private long readMonthlySharedUsageFromRedis(Long lineId) {
        if (lineId == null || lineId <= 0) {
            return 0L;
        }

        // Redis 월별 사용량은 화면 보정용 보조 데이터입니다. traffic bean이 없거나 조회 실패하면 DB 값을 유지합니다.
        TrafficRedisKeyFactory trafficRedisKeyFactory = trafficRedisKeyFactoryProvider.getIfAvailable();
        TrafficRedisRuntimePolicy trafficRedisRuntimePolicy = trafficRedisRuntimePolicyProvider.getIfAvailable();
        TrafficRemainingBalanceCacheService trafficRemainingBalanceCacheService =
                trafficRemainingBalanceCacheServiceProvider.getIfAvailable();
        if (trafficRedisKeyFactory == null
                || trafficRedisRuntimePolicy == null
                || trafficRemainingBalanceCacheService == null) {
            return 0L;
        }

        // Traffic runtime policy의 zone 기준으로 현재월 usage key를 생성합니다.
        YearMonth targetMonth = YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        String monthlySharedUsageKey = trafficRedisKeyFactory.monthlySharedUsageKey(lineId, targetMonth);
        try {
            // 조회 실패는 화면 보정 실패일 뿐이므로 0을 반환해 DB 집계값을 유지하게 합니다.
            return Math.max(0L, trafficRemainingBalanceCacheService.readValueOrDefault(monthlySharedUsageKey, 0L));
        } catch (RuntimeException e) {
            log.warn("family_shared_monthly_usage_redis_read_failed lineId={} key={}", lineId, monthlySharedUsageKey, e);
            return 0L;
        }
    }

    /**
     * null 또는 음수 값을 사용량 계산에 안전한 0 이상 값으로 보정합니다.
     */
    private long normalizeNonNegative(Long value) {
        if (value == null) {
            return 0L;
        }
        return Math.max(0L, value);
    }

    /**
     * yyyyMM 정수 요청값을 YearMonth로 변환합니다.
     */
    private YearMonth parseYearMonth(Integer yearMonth) {
        // null 또는 6자리가 아닌 값은 API 요청 파라미터 오류로 처리합니다.
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
            // DateTimeFormatter로 실제 달력 값인지 검증합니다.
            return YearMonth.parse(value, YEAR_MONTH_FORMATTER);
        } catch (DateTimeParseException exception) {
            throw new ApplicationException(
                    CommonErrorCode.INVALID_REQUEST_PARAM,
                    "yearMonth\uB294 yyyyMM \uD615\uC2DD\uC774\uC5B4\uC57C \uD569\uB2C8\uB2E4"
            );
        }
    }

    /**
     * MongoDB 공유풀 기여 로그를 히스토리 화면 DTO로 변환합니다.
     */
    private SharedPoolHistoryItemResDto toContributionHistoryItem(
            SharedPoolTransferLog log,
            Map<Long, String> userNameByLineId,
            ZoneId zoneId
    ) {
        // 기여 이벤트는 초 단위 시각을 가지므로 EVENT precision으로 표시합니다.
        return SharedPoolHistoryItemResDto.builder()
                .eventType("CONTRIBUTION")
                .title("\uB370\uC774\uD130 \uBCF4\uD0DC\uAE30")
                .userName(userNameByLineId.getOrDefault(log.getLineId(), "\uC54C \uC218 \uC5C6\uC74C"))
                .occurredAt(log.getCreatedAt().atZone(zoneId).toLocalDateTime().format(HISTORY_EVENT_FORMATTER))
                .amount(log.getAmount())
                .precision("EVENT")
                .build();
    }

    /**
     * 히스토리 정렬을 위해 일 단위/이벤트 단위 occurredAt 문자열을 LocalDateTime으로 변환합니다.
     */
    private LocalDateTime parseOccurredAtForSort(SharedPoolHistoryItemResDto item) {
        // DAY precision 사용 이력은 날짜만 있으므로 해당 일자의 시작 시각으로 정렬합니다.
        if ("DAY".equals(item.getPrecision())) {
            return LocalDate.parse(item.getOccurredAt()).atStartOfDay();
        }

        // EVENT precision 기여 이력은 yyyy-MM-dd'T'HH:mm:ss 형식의 이벤트 시각을 사용합니다.
        return LocalDateTime.parse(item.getOccurredAt(), HISTORY_EVENT_FORMATTER);
    }

    /**
     * 가족 구성원에게 가족 알림을 발송하고, 필요하면 특정 회선을 제외합니다.
     */
    private void sendAlarmToFamily(Long familyId, Long excludeLineId, AlarmType alarmType) {
        // 가족 전체 회선을 조회한 뒤 제외 대상이 아닌 회선에만 알림을 생성합니다.
        List<Long> familyLineIds = sharedPoolMapper.selectLineIdsByFamilyId(familyId);
        for (Long targetLineId : familyLineIds) {
            if (excludeLineId != null && targetLineId.equals(excludeLineId)) {
                continue;
            }
            alarmHistoryService.createAlarm(targetLineId, AlarmCode.FAMILY, alarmType);
        }
    }
}
