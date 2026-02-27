package com.pooli.policy.service;

import com.pooli.policy.domain.dto.request.AppDataLimitUpdateReqDto;
import com.pooli.policy.domain.dto.request.AppPolicyCreateReqDto;
import com.pooli.policy.domain.dto.request.AppPolicyUpdateReqDto;
import com.pooli.policy.domain.dto.request.BlockPolicyUpdateReqDto;
import com.pooli.policy.domain.dto.request.ImmediateBlockReqDto;
import com.pooli.policy.domain.dto.request.LimitPolicyUpdateReqDto;
import com.pooli.policy.domain.dto.request.RepeatBlockPolicyReqDto;
import com.pooli.policy.domain.dto.request.AppSpeedLimitUpdateReqDto;
import com.pooli.policy.domain.dto.response.ActivePolicyResDto;
import com.pooli.policy.domain.dto.response.AppPolicyResDto;
import com.pooli.policy.domain.dto.response.AppliedPolicyResDto;
import com.pooli.policy.domain.dto.response.BlockPolicyResDto;
import com.pooli.policy.domain.dto.response.ImmediateBlockResDto;
import com.pooli.policy.domain.dto.response.LimitPolicyResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import java.util.List;

public interface UserPolicyService {

    /** 백오피스에서 활성화한 전체 정책 목록 조회 */
    List<ActivePolicyResDto> getActivePolicies();

    // ── 반복적 차단 정책 (Repeat Block) ──────────────────────────────────────

    /** 특정 구성원의 반복적 차단 정책 목록 조회 */
    List<RepeatBlockPolicyResDto> getReBlockPolicies(Long lineId);

    /** 특정 구성원의 반복적 차단 정책 생성 */
    RepeatBlockPolicyResDto createReBlockPolicies(RepeatBlockPolicyReqDto request);

    /** 특정 구성원의 반복적 차단 정책 수정 */
    RepeatBlockPolicyResDto updateReBlockPolicies(Long repeatBlockId, RepeatBlockPolicyReqDto request);

    /** 특정 구성원의 반복적 차단 정책 삭제 */
    void deleteReBlockPolicies(Long repeatBlockId);

    // ── 즉시 차단 정책 (Immediate Block) ────────────────────────────────────

    /** 특정 구성원의 즉시 차단 정책 조회 */
    ImmediateBlockResDto getImBlockPolicies(Long lineId);

    /** 특정 구성원의 즉시 차단 정책 수정 */
    ImmediateBlockResDto updateImBlockPolicies(Long lineId, ImmediateBlockReqDto request);

    // ── 제한 정책 (Limit) ────────────────────────────────────────────────────

    /** 특정 구성원 제한 정책 조회 */
    LimitPolicyResDto getLimitPolicies(Long lineId);

    /** 하루 총 데이터 사용량 제한 정책 활성화/비활성화 토글 */
    LimitPolicyResDto toggleDayLimitPolicy(Long lineId);

    /** 하루 총 데이터 사용량 제한 정책 값 수정 */
    LimitPolicyResDto updateDayLimitPolicy(LimitPolicyUpdateReqDto request);

    /** 공유풀 데이터 사용량 제한 정책 활성화/비활성화 토글 */
    LimitPolicyResDto toggleShareLimitPolicy(Long lineId);

    /** 공유풀 데이터 사용량 제한 정책 값 수정 */
    LimitPolicyResDto updateShareLimitPolicy(LimitPolicyUpdateReqDto request);

    // ── 앱별 정책 (App Policy) ───────────────────────────────────────────────

    /** 특정 구성원 앱별 정책 목록 조회 */
    List<AppPolicyResDto> getAppPolicies(Long lineId);

    /** 특정 구성원 앱별 정책 신규 생성 */
    AppPolicyResDto createAppPolicy(AppPolicyCreateReqDto request);

    /** 특정 구성원 앱별 정책의 제한 데이터량 수정 */
    AppPolicyResDto updateAppPolicyLimit(AppDataLimitUpdateReqDto request);

    /** 특정 구성원 앱별 정책의 제한 속도 수정 */
    AppPolicyResDto updateAppPolicySpeed(AppSpeedLimitUpdateReqDto request);

    /** 구성원의 특정 앱 데이터 사용 정책 활성화/비활성화 토글 */
    void toggleAppPolicyEnable(Long appPolicyId);

    /** 구성원의 특정 앱 데이터 사용 정책 삭제 */
    void deleteAppPolicy(Long appPolicyId);

    // ── 적용 중인 정책 조회 ───────────────────────────────────────────────────

    /** 특정 구성원에게 현재 적용 중인 모든 정책 목록 조회 */
    AppliedPolicyResDto getAppliedPoliciesByLine(Long lineId);

    // ── Deprecated ───────────────────────────────────────────────────────────

    /** @deprecated 특정 구성원 제한 정책 정보 수정 (구버전) */
    @Deprecated
    LimitPolicyResDto updateLimitPolicy(Long lineId, Long limitPolicyId, LimitPolicyUpdateReqDto request);

    /** @deprecated 특정 구성원 반복적 차단 정책 정보 수정 (구버전) */
    @Deprecated
    BlockPolicyResDto updateBlockPolicy(Long lineId, Long blockPolicyId, BlockPolicyUpdateReqDto request);

    /** @deprecated 특정 구성원 앱별 정책 정보 수정 (구버전) */
    @Deprecated
    AppPolicyResDto updateAppPolicy(Long lineId, Long appPolicyId, AppPolicyUpdateReqDto request);

    /** @deprecated 특정 구성원 반복적 차단 정책 조회 (구버전) */
    @Deprecated
    List<BlockPolicyResDto> getBlockPolicies(Long lineId);
}
