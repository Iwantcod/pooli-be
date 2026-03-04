package com.pooli.policy.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.permission.mapper.FamilyLineMapper;
import com.pooli.policy.domain.dto.request.*;
import com.pooli.policy.domain.entity.AppPolicy;
import com.pooli.policy.domain.entity.LineLimit;
import com.pooli.policy.domain.dto.request.AppDataLimitUpdateReqDto;
import com.pooli.policy.domain.dto.request.AppSpeedLimitUpdateReqDto;
import com.pooli.policy.domain.dto.request.ImmediateBlockReqDto;
import com.pooli.policy.domain.dto.request.LimitPolicyUpdateReqDto;
import com.pooli.policy.domain.dto.request.RepeatBlockDayReqDto;
import com.pooli.policy.domain.dto.request.RepeatBlockPolicyReqDto;
import com.pooli.policy.domain.dto.response.ActivePolicyResDto;
import com.pooli.policy.domain.dto.response.AppPolicyResDto;
import com.pooli.policy.domain.dto.response.AppliedPolicyResDto;
import com.pooli.policy.domain.dto.response.ImmediateBlockResDto;
import com.pooli.policy.domain.dto.response.LimitPolicyResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockDayResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import com.pooli.policy.exception.PolicyErrorCode;
import com.pooli.policy.mapper.AppPolicyMapper;
import com.pooli.policy.mapper.LineLimitMapper;
import com.pooli.policy.mapper.ImmediateBlockMapper;
import com.pooli.policy.mapper.PolicyBackOfficeMapper;
import com.pooli.policy.mapper.RepeatBlockDayMapper;
import com.pooli.policy.mapper.RepeatBlockMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserPolicyServiceImpl implements UserPolicyService {
    private final FamilyLineMapper familyLineMapper;
    private final LineLimitMapper lineLimitMapper;
    private final AppPolicyMapper appPolicyMapper;

    private final PolicyBackOfficeMapper policyBackOfficeMapper;
    private final RepeatBlockMapper repeatBlockMapper;
    private final RepeatBlockDayMapper repeatBlockDayMapper;
    private final ImmediateBlockMapper immediateBlockMapper;

	@Override
	public List<ActivePolicyResDto> getActivePolicies() {

		return policyBackOfficeMapper.selectActivePolicies();
	}

	@Override
	public List<RepeatBlockPolicyResDto> getRepeatBlockPolicies(Long lineId, AuthUserDetails auth) {
	    checkIsSameFamilyGroup(lineId, auth.getLineId());

	    return repeatBlockMapper.selectRepeatBlocksByLineId(lineId);
	}

	@Override
	@Transactional
	public RepeatBlockPolicyResDto createRepeatBlockPolicy(RepeatBlockPolicyReqDto request, AuthUserDetails auth) {

        Long lineId = request.getLineId() != null ? request.getLineId() : auth.getLineId();
        checkIsSameFamilyGroup(lineId, auth.getLineId());
        request.setLineId(lineId);


	    List<RepeatBlockDayReqDto> days = request.getDays();

	    // 반복적 차단 요일/시간 중복 체크
	    if (days != null && !days.isEmpty()) {
	        for (RepeatBlockDayReqDto day : days) {
	            boolean exists = repeatBlockMapper.isDuplicatedRepeatBlocks(
	            		lineId,
	                    day.getDayOfWeek(),
	                    day.getStartAt(),
	                    day.getEndAt()
	            );
	            if (exists) {
	            	throw new ApplicationException(PolicyErrorCode.BLOCK_POLICY_CONFLICT);
	            }
	        }

	    }

	    // 새로운 차단이면 DB 삽입
	    repeatBlockMapper.insertRepeatBlock(request);

	    if (days != null && !days.isEmpty()) {
	        repeatBlockDayMapper.insertRepeatBlockDays(request.getRepeatBlockId(), days);
	    }

	    // DTO 반환
	    List<RepeatBlockDayResDto> dayResList = days != null
	            ? days.stream()
	                  .map(d -> RepeatBlockDayResDto.builder()
	                          .dayOfWeek(d.getDayOfWeek())
	                          .startAt(d.getStartAt())
	                          .endAt(d.getEndAt())
	                          .build())
	                  .toList()
	            : List.of();

	    return RepeatBlockPolicyResDto.builder()
	            .repeatBlockId(request.getRepeatBlockId())
	            .lineId(lineId)
	            .isActive(request.getIsActive())
	            .days(dayResList)
	            .build();
	}

	@Override
	public RepeatBlockPolicyResDto updateRepeatBlockPolicy(Long repeatBlockId, RepeatBlockPolicyReqDto request,
			AuthUserDetails auth) {
	    RepeatBlockPolicyResDto exist = repeatBlockMapper.selectRepeatBlockById(repeatBlockId);
	    if (exist == null) {
	        throw new ApplicationException(PolicyErrorCode.REPEAT_BLOCK_NOT_FOUND);
	    }
	    checkIsSameFamilyGroup(exist.getLineId(), auth.getLineId());
	    request.setLineId(exist.getLineId());
	    request.setRepeatBlockId(repeatBlockId);

		// 반복 차단 요일 및 시간대 정보를 삭제한 후 새로 삽입하기
		deleteRepeatBlockPolicy(repeatBlockId, auth);
		return createRepeatBlockPolicy(request, auth);
	}

	@Override
	public RepeatBlockPolicyResDto deleteRepeatBlockPolicy(Long repeatBlockId, AuthUserDetails auth) {

	    // 반복적 차단 정보 없음
		RepeatBlockPolicyResDto exist = repeatBlockMapper.selectRepeatBlockById(repeatBlockId);

		if (exist == null) {
	        throw new ApplicationException(PolicyErrorCode.REPEAT_BLOCK_NOT_FOUND);
	    }
		checkIsSameFamilyGroup(exist.getLineId(), auth.getLineId());

	    // soft delete
	    repeatBlockMapper.deleteRepeatBlock(repeatBlockId);
	    repeatBlockDayMapper.deleteRepeatDayBlock(repeatBlockId);

	    return RepeatBlockPolicyResDto.builder()
	    		.repeatBlockId(repeatBlockId)
	    		.lineId(auth.getLineId())
	    		.isActive(false)
	    		.build();
	}

	@Override
	public ImmediateBlockResDto getImmediateBlockPolicy(Long lineId, AuthUserDetails auth) {
	    checkIsSameFamilyGroup(lineId, auth.getLineId());

	    ImmediateBlockResDto immBlock = immediateBlockMapper.selectImmediateBlockPolicy(lineId);

	    // 즉시 차단 정보가 없을 때
	    if(immBlock == null) {
	    	return ImmediateBlockResDto.builder()
		    		.lineId(lineId)
		    		.blockEndAt(null)
		    		.build();
	    }

	    return ImmediateBlockResDto.builder()
	    		.lineId(lineId)
	    		.blockEndAt(immBlock.getBlockEndAt())
	    		.build();

	}

	@Override
	public ImmediateBlockResDto updateImmediateBlockPolicy(Long lineId, ImmediateBlockReqDto request,
			AuthUserDetails auth) {
	    checkIsSameFamilyGroup(lineId, auth.getLineId());

	    immediateBlockMapper.updateImmediateBlockPolicy(lineId, request);

        return ImmediateBlockResDto.builder()
        		.lineId(lineId)
        		.blockEndAt(request.getBlockEndAt())
        		.build();

	}

    @Override
    @Transactional(readOnly = true)
    public LimitPolicyResDto getLimitPolicy(Long lineId, AuthUserDetails auth) {
        // 1. 조회 대상 lineId가 속한 가족그룹과, 요청을 보낸 사용자의 가족 그룹이 일치하는지 검증
        checkIsSameFamilyGroup(lineId, auth.getLineId());

        // 2. 두 종류의 제한 정보 테이블에서 정보 조회
        Optional<LineLimit> lineLimit = lineLimitMapper.getExistLineLimitByLineId(lineId);
        if (lineLimit.isPresent()) {
            return LimitPolicyResDto.builder()
                    .lineLimitId(lineLimit.get().getLimitId())
                    .dailyDataLimit(lineLimit.get().getDailyDataLimit())
                    .isDailyDataLimitActive(lineLimit.get().getIsDailyLimitActive())
                    .sharedDataLimit(lineLimit.get().getSharedDataLimit())
                    .isSharedDataLimitActive(lineLimit.get().getIsSharedLimitActive())
                    .build();
        } else {
            return LimitPolicyResDto.builder().build();
        }
    }

    @Override
    @Transactional
    public LimitPolicyResDto toggleDailyTotalLimitPolicy(Long lineId, AuthUserDetails auth) {
        // 1. 조회 대상 lineId가 속한 가족그룹과, 요청을 보낸 사용자의 가족 그룹이 일치하는지 검증
        checkIsSameFamilyGroup(lineId, auth.getLineId());

        // 2. 제한 정책 존재 여부 조회
        Optional<LineLimit> lineLimit = lineLimitMapper.getExistLineLimitByLineId(lineId);
        if (lineLimit.isPresent()) {
            // 삭제 상태가 아닌 lineLimit 레코드가 존재하는 경우
            boolean newDailyLimitActive = !lineLimit.get().getIsDailyLimitActive();
            int def = lineLimitMapper.updateIsDailyLimitActiveById(lineLimit.get().getLimitId(), newDailyLimitActive);
            if(def != 1) {
                throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
            }
            return LimitPolicyResDto.builder()
                    .lineLimitId(lineLimit.get().getLimitId())
                    .dailyDataLimit(lineLimit.get().getDailyDataLimit())
                    .isDailyDataLimitActive(newDailyLimitActive)
                    .sharedDataLimit(lineLimit.get().getSharedDataLimit())
                    .isSharedDataLimitActive(lineLimit.get().getIsSharedLimitActive())
                    .build();
        } else {
            // lineLimit 레코드가 없거나, 삭제 상태인 경우 새 레코드 insert
            return insertNewLineLimit(lineId);
        }
    }

    @Override
    @Transactional
    public LimitPolicyResDto updateDailyTotalLimitPolicyValue(LimitPolicyUpdateReqDto request, AuthUserDetails auth) {
        // 1. 대상 dailyLimit 레코드 조회
        Optional<LineLimit> lineLimit = lineLimitMapper.getExistLineLimitById(request.getLimitPolicyId());
        if(lineLimit.isEmpty()) {
            throw new ApplicationException(PolicyErrorCode.LIMIT_POLICY_NOT_FOUND);
        }

        // 2. 대상 lineId가 동일 가족에 속했는지 검증
        checkIsSameFamilyGroup(lineLimit.get().getLineId(), auth.getLineId());

        // 3. update 진행
        int def = lineLimitMapper.updateDailyDataLimit(request);
        if(def != 1) {
            throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
        }

        return LimitPolicyResDto.builder()
                .lineLimitId(lineLimit.get().getLimitId())
                .dailyDataLimit(request.getPolicyValue())
                .isDailyDataLimitActive(lineLimit.get().getIsDailyLimitActive())
                .sharedDataLimit(lineLimit.get().getSharedDataLimit())
                .isSharedDataLimitActive(lineLimit.get().getIsSharedLimitActive())
                .build();
    }

    @Override
    @Transactional
    public LimitPolicyResDto toggleSharedPoolLimitPolicy(Long lineId, AuthUserDetails auth) {
        // 1. 조회 대상 lineId가 속한 가족그룹과, 요청을 보낸 사용자의 가족 그룹이 일치하는지 검증
        checkIsSameFamilyGroup(lineId, auth.getLineId());

        // 2. 제한 정책 존재 여부 조회
        Optional<LineLimit> lineLimit = lineLimitMapper.getExistLineLimitByLineId(lineId);
        if (lineLimit.isPresent()) {
            // 삭제 상태가 아닌 lineLimit 레코드가 존재하는 경우
            boolean newSharedLimitActive = !lineLimit.get().getIsSharedLimitActive();
            int def = lineLimitMapper.updateIsSharedLimitActiveById(lineLimit.get().getLimitId(), newSharedLimitActive);
            if(def != 1) {
                throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
            }
            return LimitPolicyResDto.builder()
                    .lineLimitId(lineLimit.get().getLimitId())
                    .dailyDataLimit(lineLimit.get().getDailyDataLimit())
                    .isDailyDataLimitActive(lineLimit.get().getIsDailyLimitActive())
                    .sharedDataLimit(lineLimit.get().getSharedDataLimit())
                    .isSharedDataLimitActive(newSharedLimitActive)
                    .build();
        } else {
            // lineLimit 레코드가 없거나, 삭제 상태인 경우 새 레코드 insert
            return insertNewLineLimit(lineId);
        }
    }

    /**
     * 새로운 LineLimit 레코드 삽입 후 DTO return
     * @param lineId 회선 식별자
     * @return LimitPolicyResDto
     */
    private LimitPolicyResDto insertNewLineLimit(Long lineId) {
        LineLimit newLineLimit = LineLimit.builder()
                .lineId(lineId)
                .dailyDataLimit(-1L)
                .isDailyLimitActive(true)
                .sharedDataLimit(-1L)
                .isSharedLimitActive(true)
                .build();
        int def = lineLimitMapper.createLineLimit(newLineLimit);
        if(def != 1) {
            throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
        }
        return LimitPolicyResDto.builder()
                .lineLimitId(newLineLimit.getLimitId())
                .dailyDataLimit(newLineLimit.getDailyDataLimit())
                .isDailyDataLimitActive(newLineLimit.getIsDailyLimitActive())
                .sharedDataLimit(newLineLimit.getSharedDataLimit())
                .isSharedDataLimitActive(newLineLimit.getIsSharedLimitActive())
                .build();
    }

    @Override
    @Transactional
    public LimitPolicyResDto updateSharedPoolLimitPolicyValue(LimitPolicyUpdateReqDto request, AuthUserDetails auth) {
        // 1. 대상 lineLimit 레코드 조회
        Optional<LineLimit> lineLimit = lineLimitMapper.getExistLineLimitById(request.getLimitPolicyId());
        if (lineLimit.isEmpty()) {
            throw new ApplicationException(PolicyErrorCode.LIMIT_POLICY_NOT_FOUND);
        }

        // 2. 대상 lineId가 동일 가족에 속했는지 검증
        checkIsSameFamilyGroup(lineLimit.get().getLineId(), auth.getLineId());

        // 3. update 진행
        int def = lineLimitMapper.updateSharedDataLimit(request);
        if (def != 1) {
            throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
        }

        return LimitPolicyResDto.builder()
                .lineLimitId(lineLimit.get().getLimitId())
                .dailyDataLimit(lineLimit.get().getDailyDataLimit())
                .isDailyDataLimitActive(lineLimit.get().getIsDailyLimitActive())
                .sharedDataLimit(request.getPolicyValue())
                .isSharedDataLimitActive(lineLimit.get().getIsSharedLimitActive())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppPolicyResDto> getAppPolicies(AppPolicySearchCondReqDto request, AuthUserDetails auth) {
        if (request.getPageNumber() == null || request.getPageNumber() < 0) {
            throw new ApplicationException(CommonErrorCode.INVALID_PAGE_NUMBER);
        }
        if (request.getPageSize() == null || request.getPageSize() < 0 || request.getPageSize() > 100) {
            throw new ApplicationException(CommonErrorCode.INVALID_PAGE_SIZE);
        }

        AppPolicySearchCondReqDto.PolicyScope policyScope = request.getPolicyScope() != null
                ? request.getPolicyScope()
                : AppPolicySearchCondReqDto.PolicyScope.ALL;
        AppPolicySearchCondReqDto.SortType sortType = request.getSortType() != null
                ? request.getSortType()
                : AppPolicySearchCondReqDto.SortType.ACTIVE;
        String keyword = request.getKeyword() != null && request.getKeyword().isBlank()
                ? null
                : request.getKeyword();

        AppPolicySearchCondReqDto query = AppPolicySearchCondReqDto.builder()
                .lineId(request.getLineId())
                .keyword(keyword)
                .policyScope(policyScope)
                .dataLimit(request.isDataLimit())
                .speedLimit(request.isSpeedLimit())
                .sortType(sortType)
                .pageNumber(request.getPageNumber())
                .pageSize(request.getPageSize())
                .offset(request.getPageNumber() * request.getPageSize())
                .build();
        checkIsSameFamilyGroup(query.getLineId(), auth.getLineId());
        return appPolicyMapper.findApplicationsWithPolicy(query);
    }

    @Override
    @Deprecated
    public AppPolicyResDto createAppPolicy(AppPolicyActiveToggleReqDto request, AuthUserDetails auth) {
        // 이 API는 앱 별 정책 활성화/비활성화 toggle API로 통합되었습니다.
        // toggle API 요청 시 해당 앱에 대한 정책이 기존에 존재하지 않았다면, 신규 생성 동작을 수행합니다.
        return null;
    }

    @Override
    @Transactional
    public AppPolicyResDto updateAppDataLimit(AppDataLimitUpdateReqDto request, AuthUserDetails auth) {
        // 1. 대상 appPolicy DTO 조회
        Optional<AppPolicyResDto> appPolicy = appPolicyMapper.findDtoExistById(request.getAppPolicyId());
        if (appPolicy.isEmpty()) {
            throw new ApplicationException(PolicyErrorCode.APP_POLICY_NOT_FOUND);
        }

        // 2. 대상 lineId가 동일 가족에 속했는지 검증
        checkIsSameFamilyGroup(appPolicy.get().getLineId(), auth.getLineId());

        // 3. update 진행
        int ret = appPolicyMapper.updateDataLimit(request.getAppPolicyId(), request.getValue());
        if (ret != 1) {
            throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
        }

        // 4. toBuilder()를 활용해 변경 사항이 반영된 응답 DTO 반환
        return appPolicy.get().toBuilder()
                .dailyLimitData(request.getValue())
                .build();
    }

    @Override
    @Transactional
    public AppPolicyResDto updateAppSpeedLimit(AppSpeedLimitUpdateReqDto request, AuthUserDetails auth) {
        // 1. 대상 appPolicy DTO 조회
        Optional<AppPolicyResDto> appPolicy = appPolicyMapper.findDtoExistById(request.getAppPolicyId());
        if (appPolicy.isEmpty()) {
            throw new ApplicationException(PolicyErrorCode.APP_POLICY_NOT_FOUND);
        }

        // 2. 대상 lineId가 동일 가족에 속했는지 검증
        checkIsSameFamilyGroup(appPolicy.get().getLineId(), auth.getLineId());

        // 3. update 진행
        int ret = appPolicyMapper.updateSpeedLimit(request.getAppPolicyId(), request.getValue());
        if (ret != 1) {
            throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
        }

        // 4. toBuilder()를 활용해 변경 사항이 반영된 응답 DTO 반환
        return appPolicy.get().toBuilder()
                .dailyLimitSpeed(request.getValue())
                .build();
    }

    @Override
    @Transactional
    public AppPolicyResDto toggleAppPolicyActive(AppPolicyActiveToggleReqDto request, AuthUserDetails auth) {
        // 1. 대상 lineId와 같은 가족인지 검증
        checkIsSameFamilyGroup(request.getLineId(), auth.getLineId());

        // 2. 대상 앱의 정보 및 정책 정보를 함께 조회(정책이 없어도 조회결과 존재)
        Optional<AppPolicyResDto> appPolicy = appPolicyMapper.findDtoExistByLineIdAndAppId(request.getLineId(), request.getApplicationId());
        if (appPolicy.isPresent()) {
            if(appPolicy.get().getAppPolicyId() != null) {
                // 3-1. 기존 정책이 존재한다면 is_active를 반대값으로 설정(toggle)
                int ret = appPolicyMapper.updateIsActive(appPolicy.get().getAppPolicyId(), !appPolicy.get().getIsActive());
                if(ret != 1) {
                    throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
                }
                return appPolicy.get().toBuilder()
                        .isActive(!appPolicy.get().getIsActive())
                        .build();
            } else {
                // 3-2. 기존 정책이 존재하지 않는다면 기본값 세팅하여 새 레코드 insert
                AppPolicy newAppPolicy = AppPolicy.builder()
                        .lineId(request.getLineId())
                        .applicationId(request.getApplicationId())
                        .dataLimit(-1L)
                        .speedLimit(-1)
                        .isActive(true)
                        .build();
                int ret = appPolicyMapper.insertAppPolicy(newAppPolicy);
                if(ret != 1) {
                    throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
                }
                return AppPolicyResDto.builder()
                        .appPolicyId(newAppPolicy.getAppPolicyId())
                        .lineId(request.getLineId())
                        .appId(request.getApplicationId())
                        .appName(appPolicy.get().getAppName())
                        .isActive(newAppPolicy.getIsActive())
                        .isWhiteList(false)
                        .dailyLimitData(newAppPolicy.getDataLimit())
                        .dailyLimitSpeed(newAppPolicy.getSpeedLimit())
                        .build();
            }
        } else {
            // 3-3. 조회 쿼리의 결과가 아예 존재하지 않는다면 앱이 존재하지 않음을 의미
            throw new ApplicationException(PolicyErrorCode.APP_NOT_FOUND);
        }
    }

    @Override
    @Transactional
    public AppPolicyResDto toggleAppPolicyWhitelist(Long appPolicyId, AuthUserDetails auth) {
        // 1. 대상 appPolicy DTO 조회
        Optional<AppPolicyResDto> appPolicy = appPolicyMapper.findDtoExistById(appPolicyId);
        if (appPolicy.isEmpty()) {
            throw new ApplicationException(PolicyErrorCode.APP_POLICY_NOT_FOUND);
        }

        // 2. 대상 lineId가 동일 가족에 속했는지 검증
        checkIsSameFamilyGroup(appPolicy.get().getLineId(), auth.getLineId());

        // 3. isWhiteList 토글 update 진행
        boolean newIsWhiteList = !Boolean.TRUE.equals(appPolicy.get().getIsWhiteList());
        int ret = appPolicyMapper.updateIsWhitelist(appPolicyId, newIsWhiteList);
        if (ret != 1) {
            throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
        }

        // 4. toBuilder()를 활용해 변경 사항이 반영된 응답 DTO 반환
        return appPolicy.get().toBuilder()
                .isWhiteList(newIsWhiteList)
                .build();
    }

    @Override
    @Transactional
    public void deleteAppPolicy(Long appPolicyId, AuthUserDetails auth) {
        // 삭제 대상 app policy 레코드 조회
        Optional<AppPolicy> appPolicy = appPolicyMapper.findEntityExistById(appPolicyId);
        if(appPolicy.isEmpty()) {
            // 없다면 예외 발생
            throw new ApplicationException(PolicyErrorCode.APP_POLICY_NOT_FOUND);
        }
        // 대상 레코드의 회선과 동일 가족에 속했는지 검증
        checkIsSameFamilyGroup(appPolicy.get().getLineId(), auth.getLineId());

        // soft delete
        int ret = appPolicyMapper.setDeleted(appPolicyId);
        if(ret != 1) {
            throw new ApplicationException(CommonErrorCode.DATABASE_ERROR);
        }
    }

	@Override
	public AppliedPolicyResDto getAppliedPolicies(Long lineId, AuthUserDetails auth) {
		checkIsSameFamilyGroup(lineId, auth.getLineId());

		// 제한 정책, 앱 정보 받아와서 추가하기(return에도)
	    List<RepeatBlockPolicyResDto> repeatBlockPolicyList = repeatBlockMapper.selectRepeatBlocksByLineId(lineId);

	    ImmediateBlockResDto immediateBlock = immediateBlockMapper.selectImmediateBlockPolicy(lineId);

	    // null 안전하게 처리
	    ImmediateBlockResDto immBlock = (immediateBlock != null)
	        ? ImmediateBlockResDto.builder()
	            .lineId(lineId)
	            .blockEndAt(immediateBlock.getBlockEndAt())
	            .build()
	        : null;


	    return AppliedPolicyResDto.builder()
	            .repeatBlockPolicyList(repeatBlockPolicyList)
	            .immediateBlock(immBlock)
	            .build();

	}
    /**
     * 대상 lineId가 API 요청자와 동일한 가족 그룹에 속해있는지 검증
     * @param targetLineId 대상 lineId
     * @param myLineId API 요청자 lineId
     * @throws ApplicationException '접근 권한 없음' 예외 throws
     */
    private void checkIsSameFamilyGroup(Long targetLineId, Long myLineId) throws ApplicationException {
        List<Long> myFamilyLineIdList = familyLineMapper.findAllFamilyIdByLineId(myLineId);

        if(!myFamilyLineIdList.contains(targetLineId)){
            // 동일한 가족 그룹에 속해있지 않다면 권한 없음을 의미
            throw new ApplicationException(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN);
        }
    }

}
