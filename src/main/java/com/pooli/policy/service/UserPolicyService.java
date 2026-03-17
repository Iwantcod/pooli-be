package com.pooli.policy.service;

import java.util.List;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.dto.PagingResDto;
import com.pooli.policy.domain.dto.request.AppDataLimitUpdateReqDto;
import com.pooli.policy.domain.dto.request.AppPolicyActiveToggleReqDto;
import com.pooli.policy.domain.dto.request.AppPolicySearchCondReqDto;
import com.pooli.policy.domain.dto.request.AppSpeedLimitUpdateReqDto;
import com.pooli.policy.domain.dto.request.BlockPolicyUpdateReqDto;
import com.pooli.policy.domain.dto.request.ImmediateBlockReqDto;
import com.pooli.policy.domain.dto.request.LimitPolicyUpdateReqDto;
import com.pooli.policy.domain.dto.request.RepeatBlockPolicyReqDto;
import com.pooli.policy.domain.dto.response.ActivePolicyResDto;
import com.pooli.policy.domain.dto.response.AppPolicyResDto;
import com.pooli.policy.domain.dto.response.AppliedPolicyResDto;
import com.pooli.policy.domain.dto.response.BlockPolicyResDto;
import com.pooli.policy.domain.dto.response.BlockStatusResDto;
import com.pooli.policy.domain.dto.response.ImmediateBlockResDto;
import com.pooli.policy.domain.dto.response.LimitPolicyResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;

public interface UserPolicyService {

    // =========================================================
    // 0) Global / Backoffice
    // =========================================================

    /**
     * 백오피스에서 '활성화'한 전체 정책 목록 조회
     * Controller: ResponseEntity<List<ActivePolicyResDto>>
     */
    List<ActivePolicyResDto> getActivePolicies();


    // =========================================================
    // 1) Block Policies - Repeat Block
    // =========================================================

    /**
     * 특정 구성원의 반복적 차단 정책 목록 조회
     * Controller: ResponseEntity<List<RepeatBlockPolicyResDto>>
     */
    List<RepeatBlockPolicyResDto> getRepeatBlockPolicies(Long lineId, AuthUserDetails auth);

    /**
     * 특정 구성원의 반복적 차단 정책 생성
     * Controller: ResponseEntity<RepeatBlockPolicyResDto>
     */
    RepeatBlockPolicyResDto createRepeatBlockPolicy(RepeatBlockPolicyReqDto request, AuthUserDetails auth);

    /**
     * 특정 구성원의 반복적 차단 정책 수정
     * Controller: ResponseEntity<RepeatBlockPolicyResDto>
     */
    RepeatBlockPolicyResDto updateRepeatBlockPolicy(Long repeatBlockId,
                                                    RepeatBlockPolicyReqDto request,
                                                    AuthUserDetails auth);

    /**
     * 특정 구성원의 반복적 차단 정책 삭제
     * Controller: ResponseEntity<RepeatBlockPolicyResDto>
     *
     * 주의: 삭제 결과로 "삭제된 정책 DTO"를 내려주려면,
     * 삭제 직전 스냅샷을 만들거나 소프트 삭제 레코드를 조회해 반환해야 합니다.
     */
    RepeatBlockPolicyResDto deleteRepeatBlockPolicy(Long repeatBlockId, AuthUserDetails auth);

    BlockPolicyResDto toggleRepeatBlockPolicy(Long repeatBlockId, BlockPolicyUpdateReqDto request, AuthUserDetails auth);
    // =========================================================
    // 2) Block Policies - Immediate Block
    // =========================================================

    /**
     * 현재 시각 기준으로 즉시 차단 또는 반복 차단 중인지 여부와 종료 시각 조회
     * Controller: ResponseEntity<BlockStatusResDto>
     */
    BlockStatusResDto getBlockStatus(Long lineId, AuthUserDetails auth);

    /**
     * 특정 구성원의 즉시 차단 정책 조회
     * Controller: ResponseEntity<ImmediateBlockResDto>
     */
    ImmediateBlockResDto getImmediateBlockPolicy(Long lineId, AuthUserDetails auth);

    /**
     * 특정 구성원의 즉시 차단 정책 수정
     * Controller: ResponseEntity<ImmediateBlockResDto>
     */
    ImmediateBlockResDto updateImmediateBlockPolicy(Long lineId,
                                                    ImmediateBlockReqDto request,
                                                    AuthUserDetails auth);


    // =========================================================
    // 3) Limit & Share Policies
    // =========================================================

    /**
     * 특정 구성원 제한 정책 조회
     * Controller: ResponseEntity<LimitPolicyResDto>
     */
    LimitPolicyResDto getLimitPolicy(Long lineId, AuthUserDetails auth);

    /**
     * 특정 구성원의 하루 총 데이터 사용량 제한 정책 (신규 추가 or 활성화)/비활성화
     * Controller: ResponseEntity<LimitPolicyResDto>
     */
    LimitPolicyResDto toggleDailyTotalLimitPolicy(Long lineId, AuthUserDetails auth);

    /**
     * 특정 구성원의 하루 총 데이터 사용량 제한 정책 값 수정
     * Controller: ResponseEntity<LimitPolicyResDto>
     */
    LimitPolicyResDto updateDailyTotalLimitPolicyValue(LimitPolicyUpdateReqDto request,
                                                       AuthUserDetails auth);

    /**
     * 특정 구성원의 공유풀 데이터 사용량 제한 정책 (신규 추가 or 활성화)/비활성화
     * Controller: ResponseEntity<LimitPolicyResDto>
     */
    LimitPolicyResDto toggleSharedPoolLimitPolicy(Long lineId, AuthUserDetails auth);

    /**
     * 특정 구성원의 공유풀 데이터 사용량 제한 정책 값 수정
     * Controller: ResponseEntity<LimitPolicyResDto>
     */
    LimitPolicyResDto updateSharedPoolLimitPolicyValue(LimitPolicyUpdateReqDto request,
                                                       AuthUserDetails auth);


    // =========================================================
    // 4) App Policies
    // =========================================================

    /**
     * 특정 구성원 앱 별 정책 조회
     * Controller: ResponseEntity<PagingResDto<AppPolicyResDto>>
     */
    PagingResDto<AppPolicyResDto> getAppPolicies(AppPolicySearchCondReqDto request, AuthUserDetails auth);

    /**
     * 특정 구성원 앱별 정책 신규 생성
     * Controller: ResponseEntity<AppPolicyResDto>
     */
    @Deprecated
    AppPolicyResDto createAppPolicy(AppPolicyActiveToggleReqDto request, AuthUserDetails auth);

    /**
     * 특정 구성원 앱별 정책의 제한 데이터량(단위: Byte) 수정
     * Controller: ResponseEntity<AppPolicyResDto>
     */
    AppPolicyResDto updateAppDataLimit(AppDataLimitUpdateReqDto request, AuthUserDetails auth);

    /**
     * 특정 구성원 앱별 정책의 제한 속도(단위: Kbps) 수정
     * Controller: ResponseEntity<AppPolicyResDto>
     */
    AppPolicyResDto updateAppSpeedLimit(AppSpeedLimitUpdateReqDto request, AuthUserDetails auth);

    /**
     * 구성원의 특정 앱 데이터 사용 정책 활성화(or 신규생성)/비활성화 토글 요청
     * Controller: ResponseEntity<AppPolicyResDto>
     */
    AppPolicyResDto toggleAppPolicyActive(AppPolicyActiveToggleReqDto request, AuthUserDetails auth);

    /**
     * 구성원의 특정 앱 데이터 사용 정책 화이트리스트 적용 여부 토글 요청
     * Controller: ResponseEntity<AppPolicyResDto>
     */
    AppPolicyResDto toggleAppPolicyWhitelist(Long appPolicyId, AuthUserDetails auth);

    /**
     * 구성원의 특정 앱 데이터 사용 정책 삭제
     * Controller: ResponseEntity<Void>
     */
    void deleteAppPolicy(Long appPolicyId, AuthUserDetails auth);


    // =========================================================
    // 5) Applied Status
    // =========================================================

    /**
     * 특정 구성원에게 적용 중인 모든 정책 목록 조회(현재 적용 중인 정책)
     * Controller: ResponseEntity<AppliedPolicyResDto>
     */
    AppliedPolicyResDto getAppliedPolicies(Long lineId, AuthUserDetails auth);
}
