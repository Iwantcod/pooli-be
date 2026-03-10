package com.pooli.policy.controller;

import java.util.List;

import com.pooli.policy.domain.dto.request.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.dto.PagingResDto;
import com.pooli.policy.domain.dto.response.ActivePolicyResDto;
import com.pooli.policy.domain.dto.response.AppPolicyResDto;
import com.pooli.policy.domain.dto.response.AppliedPolicyResDto;
import com.pooli.policy.domain.dto.response.BlockStatusResDto;
import com.pooli.policy.domain.dto.response.BlockPolicyResDto;
import com.pooli.policy.domain.dto.response.ImmediateBlockResDto;
import com.pooli.policy.domain.dto.response.LimitPolicyResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import com.pooli.policy.domain.enums.PolicyScope;
import com.pooli.policy.domain.enums.SortType;
import com.pooli.policy.service.UserPolicyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Policy", description = "정책 API")
@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class UserPolicyController {

	private final UserPolicyService userPolicyService;

    @Operation(
            summary = "백오피스에서 '활성화'한 전체 정책 목록 조회",
            description = "사용자 권한 필요. 가족 대표가 가족 그룹에 적용할 수 있는 활성화 정책 목록을 조회합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "활성화 정책 목록 조회 요청 성공"),
        @ApiResponse(
            responseCode = "403",
            description = """
                가족 대표자 권한 없음
                - COMMON:4300 가족 대표자 권한 없음
                """
        ),
        @ApiResponse(
            responseCode = "500",
            description = "서버 내부 오류"
        )
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @GetMapping
    public ResponseEntity<List<ActivePolicyResDto>> getActivePolicies(  @AuthenticationPrincipal AuthUserDetails auth) {

        return ResponseEntity.ok(userPolicyService.getActivePolicies());
    }

    @Operation(
            summary = "특정 구성원의 반복적 차단 정책 목록 조회",
            description = "특정 구성원의 회선 ID 필요. 해당 회선의 반복적 차단 정책 목록을 조회합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "반복적 차단 정책 목록 조회 요청 성공"),
        @ApiResponse(
            responseCode = "400",
            description = """
                잘못된 요청
                - COMMON:4002 RequestParam 유효성 검증 실패
                - COMMON:4003 RequestParam 타입 불일치
                - COMMON:4004 필수 RequestParam 누락
                """
        ),
        @ApiResponse(
            responseCode = "403",
            description = """
                가족 대표자 권한 없음
                - COMMON:4300 가족 대표자 권한이 없음
                """
        ),
        @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/lines/repeat-block")
    public ResponseEntity<List<RepeatBlockPolicyResDto>> getReBlockPolicies(
    		@AuthenticationPrincipal AuthUserDetails auth,
            @Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam("lineId") Long lineId
    ) {

        return ResponseEntity.ok(userPolicyService.getRepeatBlockPolicies(lineId, auth));
    }

    @Operation(
            summary = "특정 구성원의 반복적 차단 정책 생성",
            description = "특정 구성원의 반복적 차단 정책을 생성합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "요청 성공"),
        @ApiResponse(
            responseCode = "400",
            description = """
                잘못된 요청
                
                - COMMON:4000 요청 형식 불일치
                - COMMON:4001 요청 DTO 필드 유효성 검증 실패
                - COMMON:4006 Content-Type 불일치
                - POLICY:4000 차단 시작 시간은 종료 시간보다 이전이어야 함
                - POLICY:4001 차단/종료 시간 누락
                - POLICY:4002 차단 기간 24시간 미만으로 설정 필수
                """
        ),
        @ApiResponse(
            responseCode = "403",
            description = """
                가족 대표자 권한 없음
                
                - COMMON:4300 가족 대표자 권한이 없음
                """
        ),
        @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
        @ApiResponse(
            responseCode = "409",
            description = """
                정책 충돌
                - POLICY:4904 기존의 차단 정책과 충돌
                """
        ),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @PostMapping("/lines/repeat-block")
    public ResponseEntity<RepeatBlockPolicyResDto> createReBlockPolicies(

    		@AuthenticationPrincipal AuthUserDetails auth,
            @RequestBody RepeatBlockPolicyReqDto request
    ) {

        RepeatBlockPolicyResDto response = userPolicyService.createRepeatBlockPolicy(request, auth);

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원의 반복적 차단 정책 수정",
            description = "특정 구성원의 반복적 차단 ID 필요. 반복적 차단 정책을 수정합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "반복적 차단 정책 수정 요청 성공"),
        @ApiResponse(
            responseCode = "400",
            description = """
                잘못된 요청
                
                - COMMON:4000 요청 형식 불일치
                - COMMON:4001 요청 DTO 필드 유효성 검증 실패
                - COMMON:4002 RequestParam 유효성 검증 실패
                - COMMON:4003 RequestParam 타입 불일치
                - COMMON:4004 필수 RequestParam 누락
                - COMMON:4006 Content-Type 불일치
                - POLICY:4000 차단 시작 시간은 종료 시간보다 이전이어야 함
                - POLICY:4001 차단/종료 시간 누락
                - POLICY:4002 차단 기간 24시간 미만으로 설정 필수
                """
        ),
        @ApiResponse(
            responseCode = "403",
            description = """
                가족 대표자 권한 없음
                - COMMON:4300 가족 대표자 권한이 없음
                """
        ),
        @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
        @ApiResponse(responseCode = "409", description = "정책 충돌"),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @PatchMapping("/lines/repeat-block")
    public ResponseEntity<RepeatBlockPolicyResDto> updateReBlockPolicies(
    		@AuthenticationPrincipal AuthUserDetails auth,
            @Parameter(name = "repeatBlockId", description = "반복적 차단 식별자", example = "202")
            @RequestParam("repeatBlockId") Long repeatBlockId,
            @RequestBody RepeatBlockPolicyReqDto request
    ) {
        RepeatBlockPolicyResDto response = userPolicyService.updateRepeatBlockPolicy(repeatBlockId, request, auth);

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원의 반복적 차단 정책 삭제",
            description = "특정 구성원의 반복적 차단 ID 필요. 반복적 차단 정책을 삭제합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "요청 성공"),
        @ApiResponse(
            responseCode = "400",
            description = """
                잘못된 요청
                
                - COMMON:4002 RequestParam 유효성 검증 실패
                - COMMON:4003 RequestParam 타입 불일치
                - COMMON:4004 필수 RequestParam 누락
                """
        ),
        @ApiResponse(
            responseCode = "403",
            description = """
                가족 대표자 권한 없음
                
                - COMMON:4300 가족 대표자 권한이 없음
                """
        ),
        @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
        @ApiResponse(responseCode = "409", description = "정책 충돌"),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @DeleteMapping("/lines/repeat-block")
    public ResponseEntity<RepeatBlockPolicyResDto> deleteReBlockPolicies(
    		@AuthenticationPrincipal AuthUserDetails auth,
            @Parameter(name = "repeatBlockId", description = "반복적 차단 식별자", example = "202")
            @RequestParam("repeatBlockId") Long repeatBlockId
    ) {

    	RepeatBlockPolicyResDto response = userPolicyService.deleteRepeatBlockPolicy(repeatBlockId, auth);

        return ResponseEntity.ok(response);
    }
    
    @Operation(
            summary = "특정 구성원의 즉시 차단 정책 조회",
            description = "특정 구성원의 회선 ID 필요. 해당 회선의 즉시 차단 정책 내용을 조회합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "즉시 차단 정책 조회 요청 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
                    - COMMON:4002 RequestParam 유효성 검증 실패
                    - COMMON:4003 RequestParam 타입 불일치
                    - COMMON:4004 필수 RequestParam 누락
                    """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    가족 대표자 권한 없음
                    
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
        @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/lines/immediate-block")
    public ResponseEntity<ImmediateBlockResDto> getImBlockPolicies(
    		@AuthenticationPrincipal AuthUserDetails auth,
            @Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam("lineId") Long lineId
    ) {

        ImmediateBlockResDto response = userPolicyService.getImmediateBlockPolicy(lineId, auth);

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원의 즉시 차단 정책 수정",
            description = "특정 구성원의 회선 ID 필요. 해당 회선의 즉시 차단 정책 내용을 수정합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "즉시 차단 정책 수정 요청 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
                    - COMMON:4000 요청 형식 불일치
	                - COMMON:4001 요청 DTO 필드 유효성 검증 실패
	                - COMMON:4002 RequestParam 유효성 검증 실패
	                - COMMON:4003 RequestParam 타입 불일치
	                - COMMON:4004 필수 RequestParam 누락
	                - COMMON:4006 Content-Type 불일치
	                - POLICY:4000 차단 시작 시간은 종료 시간보다 이전이어야 함
	                - POLICY:4001 차단/종료 시간 누락
	                - POLICY:4002 차단 기간 24시간 미만으로 설정 필수		                
                    """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    가족 대표자 권한 없음
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
        @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
        @ApiResponse(responseCode = "409", description = "정책 충돌"),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @PatchMapping("/lines/immediate-block")
    public ResponseEntity<ImmediateBlockResDto> updateImBlockPolicies(
    		@AuthenticationPrincipal AuthUserDetails auth,
            @Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam("lineId") Long lineId,
            @RequestBody ImmediateBlockReqDto request
    ) {

        ImmediateBlockResDto response = userPolicyService.updateImmediateBlockPolicy(lineId, request, auth);

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원 제한 정책 조회",
            description = "사용자 권한 필요. 특정 회선의 제한 정책 항목을 조회합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "제한 정책 조회 요청 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
	                - COMMON:4002 RequestParam 유효성 검증 실패
	                - COMMON:4003 RequestParam 타입 불일치
	                - COMMON:4004 필수 RequestParam 누락           
                    """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    가족 대표자 권한 없음
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
        @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/lines/limits")
    public ResponseEntity<LimitPolicyResDto> getLimitPolicies(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId,
            @AuthenticationPrincipal AuthUserDetails auth
            ) {
        LimitPolicyResDto answer = userPolicyService.getLimitPolicy(lineId, auth);
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "특정 구성원의 하루 총 데이터 사용량 제한 제한 정책 (신규 추가 or 활성화)/비활성화",
            description = "제한 정책을 활성화하거나, 활성화했던 적이 없다면 신규 추가합니다. 혹은 비활성화합니다. 가족 대표자 권한이 필요합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "하루 총 데이터 사용량 제한 정책 활성화/비활성화 요청 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
	                - COMMON:4002 RequestParam 유효성 검증 실패
	                - COMMON:4003 RequestParam 타입 불일치
	                - COMMON:4004 필수 RequestParam 누락           
                    """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    가족 대표자 권한 없음
                    
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
        @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
        @ApiResponse(responseCode = "409", description = "정책 충돌"),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @PatchMapping("/lines/days/limits/enable-toggles")
    public ResponseEntity<LimitPolicyResDto> toggleDayLimitPolicy(
            @Parameter(description = "회선 식별자", example = "1")
            @RequestParam Long lineId,
            @AuthenticationPrincipal AuthUserDetails auth
    ) {
        LimitPolicyResDto answer = userPolicyService.toggleDailyTotalLimitPolicy(lineId, auth);
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "특정 구성원의 하루 총 데이터 사용량 제한 정책 값 수정",
            description = "슬라이드로 결정된 값으로 수정합니다. 가족 대표자 권한이 필요합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "하루 총 데이터 사용량 제한 값 수정 요청 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
                    - COMMON:4000 요청 형식 불일치
                    - COMMON:4001 요청 DTO 필드 유효성 검증 실패
                    - COMMON:4006 Content-Type 불일치
                    - POLICY:4003 제한할 수 있는 데이터의 범위 초과
                    """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    가족 대표자 권한 없음
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
        @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
        @ApiResponse(responseCode = "409", description = "정책 충돌"),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @PatchMapping("/lines/days/limits")
    public ResponseEntity<LimitPolicyResDto> updateDayLimitPolicy(
            @RequestBody LimitPolicyUpdateReqDto request,
            @AuthenticationPrincipal AuthUserDetails auth
    ) {
        LimitPolicyResDto answer = userPolicyService.updateDailyTotalLimitPolicyValue(request, auth);
        return ResponseEntity.ok(answer);
    }
    
    @Operation(
            summary = "특정 구성원의 공유풀 데이터 사용량 제한 제한 정책 (신규 추가 or 활성화)/비활성화",
            description = "제한 정책을 활성화하거나, 활성화했던 적이 없다면 신규 추가합니다. 혹은 비활성화합니다. 가족 대표자 권한이 필요합니다."
    )
    @ApiResponses({
	    @ApiResponse(responseCode = "200", description = "데이터 사용량 제한 활성화/비활성화 요청 성공"),
	    @ApiResponse(
	            responseCode = "400",
	            description = """
	                잘못된 요청
	                - COMMON:4002 RequestParam 유효성 검증 실패
	                - COMMON:4003 RequestParam 타입 불일치
	                - COMMON:4004 필수 RequestParam 누락
	                """
	        ),
	    @ApiResponse(
	            responseCode = "403",
	            description = """
	                가족 대표자 권한 없음
	                - COMMON:4300 가족 대표자 권한이 없음
	                """
	        ),
	    @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
	    @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @PatchMapping("/lines/shares/limits/enable-toggles")
    public ResponseEntity<LimitPolicyResDto> toggleShareLimitPolicy(
            @Parameter(description = "회선 식별자", example = "1")
            @RequestParam Long lineId,
            @AuthenticationPrincipal AuthUserDetails auth
    ) {
        LimitPolicyResDto answer = userPolicyService.toggleSharedPoolLimitPolicy(lineId, auth);
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "특정 구성원의 공유풀 데이터 사용량 제한 정책 값 수정",
            description = "슬라이드로 결정된 값으로 수정합니다. 가족 대표자 권한이 필요합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "공유풀 데이터 사용량 제한 값 수정 요청 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
                    - COMMON:4000 요청 형식 불일치
                    - COMMON:4001 요청 DTO 필드 유효성 검증 실패
                    - COMMON:4006 Content-Type 불일치
                    - POLICY:4003 제한할 수 있는 데이터의 범위 초과
                    """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    가족 대표자 권한 없음
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
        @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
        @ApiResponse(responseCode = "409", description = "정책 충돌"),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @PatchMapping("/lines/shares/limits")
    public ResponseEntity<LimitPolicyResDto> updateShareLimitPolicy(
            @RequestBody LimitPolicyUpdateReqDto request,
            @AuthenticationPrincipal AuthUserDetails auth
    ) {
        LimitPolicyResDto answer = userPolicyService.updateSharedPoolLimitPolicyValue(request, auth);
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "특정 구성원 앱 별 정책 동적 필터링 & 정렬 기준 적용 조회",
            description = """
                    사용자 권한 필요. 특정 회선(lineId)의 앱 정책 목록을 동적 필터/정렬/페이지네이션으로 조회합니다.
                    
                    API URI 예시
                    - 기본 조회: GET /api/policies/lines/apps?lineId=101
                    - 키워드 + 정렬: GET /api/policies/lines/apps?lineId=101&keyword=You&sortType=NAME
                    - 정책 적용 앱만: GET /api/policies/lines/apps?lineId=101&policyScope=APPLIED
                    - 데이터/속도 제한 필터: GET /api/policies/lines/apps?lineId=101&dataLimit=true&speedLimit=true
                    - 페이지 조회: GET /api/policies/lines/apps?lineId=101&pageNumber=1&pageSize=20
                    
                    응답 필드 매핑 기준
                    - appId, appName: APPLICATION 테이블 값으로 항상 제공
                    - appPolicyId, lineId, isActive, dailyLimitData, dailyLimitSpeed, isWhiteList
                        - APP_POLICY가 존재하지 않는 경우: 해당 필드 모두 null
                        - APP_POLICY가 존재하지 않는 경우: 해당 필드에 DB 값 반영
                    
                    파라미터 참고
                    - pageNumber 기본값: 0
                    - pageSize 기본값: 100 (허용 범위: 0~100)
                    - policyScope(정책 적용 상태) 기본값: ALL
                    - sortType(정렬 기준) 기본값: ACTIVE(활성화순)
                    """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "앱 단위 정책 목록, pk 조회 요청 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
	                - COMMON:4002 RequestParam 유효성 검증 실패
	                - COMMON:4003 RequestParam 타입 불일치
	                - COMMON:4004 필수 RequestParam 누락
	                """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    가족 대표자 권한 없음
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
        @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/lines/apps")
    public ResponseEntity<PagingResDto<AppPolicyResDto>> getAppPolicies(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId,
            @Parameter(description = "조회할 페이지 번호", example = "0")
            @RequestParam(defaultValue = "0") Integer pageNumber,
            @Parameter(description = "페이지 당 크기", example = "10")
            @RequestParam(defaultValue = "100") Integer pageSize,
            @Parameter(description = "앱 이름 검색 키워드", example = "You")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "정책 상태 필터", example = "ALL")
            @RequestParam(required = false, defaultValue = "ALL") PolicyScope policyScope,
            @Parameter(description = "데이터 제한 적용 앱만 조회 여부", example = "false")
            @RequestParam(required = false, defaultValue = "false") boolean dataLimit,
            @Parameter(description = "속도 제한 적용 앱만 조회 여부", example = "false")
            @RequestParam(required = false, defaultValue = "false") boolean speedLimit,
            @Parameter(description = "정렬 기준", example = "ACTIVE")
            @RequestParam(required = false, defaultValue = "ACTIVE") SortType sortType,
            @AuthenticationPrincipal AuthUserDetails auth
    ) {
        AppPolicySearchCondReqDto request = AppPolicySearchCondReqDto.builder()
                .lineId(lineId)
                .pageNumber(pageNumber)
                .pageSize(pageSize)
                .keyword(keyword)
                .policyScope(policyScope)
                .dataLimit(dataLimit)
                .speedLimit(speedLimit)
                .sortType(sortType)
                .build();

        PagingResDto<AppPolicyResDto> answer = userPolicyService.getAppPolicies(request, auth);
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "특정 구성원 앱별 정책의 제한 데이터량(단위: Byte) 수정",
            description = "가족 대표자 권한 필요, value 단위: Byte"
    )
    @ApiResponses({
	    @ApiResponse(responseCode = "200", description = "특정 구성원 앱별 정책의 제한 데이터량 수정 요청 성공"),
	    @ApiResponse(
	            responseCode = "400",
	            description = """
	            	잘못된 요청
	                - COMMON:4000 요청 형식 불일치
	                - COMMON:4001 DTO 유효성 검증 실패
	                - COMMON:4006 Content-Type 불일치
	                - POLICY:4003 제한할 수 있는 데이터 범위 초과
	                """
	        ),
	    @ApiResponse(
	            responseCode = "403",
	            description = """
	                가족 대표자 권한 없음
	                - COMMON:4300 가족 대표자 권한이 없음
	                """
	        ),
	    @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
	    @ApiResponse(responseCode = "500", description = "서버 내부 오류")
	})
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @PatchMapping("/lines/apps/limits")
    public ResponseEntity<AppPolicyResDto> updateAppPolicyLimit(
            @RequestBody AppDataLimitUpdateReqDto request,
            @AuthenticationPrincipal AuthUserDetails auth
    ) {
        AppPolicyResDto answer = userPolicyService.updateAppDataLimit(request, auth);
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "특정 구성원 앱별 정책의 제한 속도(단위: Kbps) 수정",
            description = "가족 대표자 권한 필요, value 단위: Kbps(ex: 1Mbps == 1000Kbps)"
    )
    @ApiResponses({
	    @ApiResponse(responseCode = "200", description = "특정 구성원 앱별 정책의 제한 속도 수정 요청 성공"),
	    @ApiResponse(
	            responseCode = "400",
	            description = """
	            	잘못된 요청
	                - COMMON:4000 요청 형식 불일치
	                - COMMON:4001 DTO 유효성 검증 실패
	                - COMMON:4006 Content-Type 불일치
	                - POLICY:4003 제한할 수 있는 데이터 범위 초과
	                """
	        ),
	    @ApiResponse(
	            responseCode = "403",
	            description = """
	                가족 대표자 권한 없음
	                - COMMON:4300 가족 대표자 권한이 없음
	                """
	        ),
	    @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
	    @ApiResponse(responseCode = "500", description = "서버 내부 오류")
	})
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @PatchMapping("/lines/apps/speeds")
    public ResponseEntity<AppPolicyResDto> updateAppPolicySpeed(
            @RequestBody AppSpeedLimitUpdateReqDto request,
            @AuthenticationPrincipal AuthUserDetails auth
    ) {
        AppPolicyResDto response = userPolicyService.updateAppSpeedLimit(request, auth);
        return ResponseEntity.ok(response);
    }


    @Operation(
            summary = "구성원의 특정 앱 데이터 사용 정책 활성화(or 신규생성)/비활성화 토글 요청",
            description = "가족 대표자 권한 필요"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
            잘못된 요청
            - COMMON:4002 RequestParam 유효성 검증 실패
            - COMMON:4003 RequestParam 타입 불일치
            - COMMON:4004 필수 RequestParam 누락
            """
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = """
            권한이 없음
            - COMMON:4300 가족 대표자 권한이 없습니다.
            """
            ),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @PatchMapping("/lines/apps/enable-toggles")
    public ResponseEntity<AppPolicyResDto> toggleAppPolicyEnable(
            @RequestBody AppPolicyActiveToggleReqDto request,
            @AuthenticationPrincipal AuthUserDetails auth
    ) {
        AppPolicyResDto answer = userPolicyService.toggleAppPolicyActive(request, auth);
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "구성원의 특정 앱 데이터 사용 정책 화이트리스트 적용 여부 토글 요청",
            description = "가족 대표자 권한 필요"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
            잘못된 요청
            - COMMON:4002 RequestParam 유효성 검증 실패
            - COMMON:4003 RequestParam 타입 불일치
            - COMMON:4004 필수 RequestParam 누락
            """
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = """
            권한이 없음
            - COMMON:4300 가족 대표자 권한이 없습니다.
            """
            ),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @PatchMapping("/lines/apps/whitelist-toggles")
    public ResponseEntity<AppPolicyResDto> toggleAppPolicyWhitelist(
            @Parameter(description = "앱 정책 식별자", example = "154")
            @RequestParam Long appPolicyId,
            @AuthenticationPrincipal AuthUserDetails auth
    ) {
        AppPolicyResDto answer = userPolicyService.toggleAppPolicyWhitelist(appPolicyId, auth);
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "구성원의 특정 앱 데이터 사용 정책 삭제",
            description = "가족 대표자 권한 필요"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
            잘못된 요청
            - COMMON:4002 RequestParam 유효성 검증 실패
            - COMMON:4003 RequestParam 타입 불일치
            - COMMON:4004 필수 RequestParam 누락
            """
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = """
            권한이 없음
            - COMMON:4300 가족 대표자 권한이 없습니다.
            """
            ),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @DeleteMapping("/lines/apps")
    public ResponseEntity<Void> deleteAppPolicy(
            @Parameter(description = "앱 정책 식별자", example = "154")
            @RequestParam Long appPolicyId,
            @AuthenticationPrincipal AuthUserDetails auth
    ) {
        userPolicyService.deleteAppPolicy(appPolicyId, auth);
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "특정 구성원에게 적용 중인 모든 정책 목록 조회(현재 적용 중인 정책)",
            description = "사용자 권한 필요. 특정 회선에 현재 적용 중인 정책 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
            잘못된 요청
            - COMMON:4002 RequestParam 유효성 검증 실패
            - COMMON:4003 RequestParam 타입 불일치
            - COMMON:4004 필수 RequestParam 누락
            """
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = """
            권한이 없음
            - COMMON:4300 가족 대표자 권한이 없습니다.
            """
            ),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/lines/applied")
    public ResponseEntity<AppliedPolicyResDto> getAppliedPoliciesByLine(
    		@AuthenticationPrincipal AuthUserDetails auth,
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam("lineId") Long lineId
    ) {
    	return ResponseEntity.ok(userPolicyService.getAppliedPolicies(lineId, auth));
    }

    @Operation(
            summary = "특정 구성원 제한 정책 정보 수정",
            description = "사용자 권한 필요. 제한 정책 PK 기준으로 1건만 수정하고 PK와 값을 응답합니다.",
            deprecated = true
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/lines/limits")
    public ResponseEntity<LimitPolicyResDto> updateLimitPolicy(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId,
            @Parameter(description = "제한 정책 PK", example = "7201")
            @RequestParam Long limitPolicyId,
            @RequestBody LimitPolicyUpdateReqDto request
    ) {
        LimitPolicyResDto response = LimitPolicyResDto.builder()
                .build();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원 반복적 차단 정책 정보 수정",
            description = "사용자 권한 필요. 차단 정책 PK 기준으로 1건만 수정하고 PK와 값을 응답합니다.",
            deprecated = true
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/lines/blocks")
    public ResponseEntity<BlockPolicyResDto> updateBlockPolicy(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId,
            @Parameter(description = "차단 정책 PK", example = "7101")
            @RequestParam Long blockPolicyId,
            @RequestBody BlockPolicyUpdateReqDto request
    ) {
        BlockPolicyResDto response = BlockPolicyResDto.builder()
                .build();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원 앱별 정책 정보 수정",
            description = "사용자 권한 필요. 앱 정책 PK 기준으로 1건만 수정하고 PK와 값을 응답합니다.",
            deprecated = true
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/lines/apps")
    public ResponseEntity<AppPolicyResDto> updateAppPolicy(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId,
            @Parameter(description = "앱 정책 PK", example = "7301")
            @RequestParam Long appPolicyId,
            @RequestBody AppPolicyUpdateReqDto request
    ) {
        AppPolicyResDto response = AppPolicyResDto.builder().build();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원 반복적 차단 정책 조회",
            description = "사용자 권한 필요. 특정 회선의 차단 정책 항목과 PK를 조회합니다.",
            deprecated = true
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/lines/blocks")
    public ResponseEntity<List<BlockPolicyResDto>> getBlockPolicies(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId
    ) {
        List<BlockPolicyResDto> response = List.of(
                BlockPolicyResDto.builder()
                        .build(),
                BlockPolicyResDto.builder()
                        .build()
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원 앱별 정책 신규 생성",
            description = "가족 대표자만 설정 가능합니다.",
            deprecated = true
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
                    잘못된 요청
                        
	                - COMMON:4000 요청 형식 불일치
	                - COMMON:4001 DTO 유효성 검증 실패
	                - COMMON:4006 Content-Type 불일치
	                """
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = """
                    가족 대표자 권한 없음
                    
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "정책 충돌"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @PostMapping("/lines/apps")
    public ResponseEntity<AppPolicyResDto> createAppPolicy(
            @RequestBody AppPolicyActiveToggleReqDto request
    ) {
        AppPolicyResDto response = AppPolicyResDto.builder().build();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원의 현재 차단 상태 조회",
            description = """
                    사용자 권한 필요. 특정 회선의 현재 시각 기준 차단 여부와 차단 종료 시각을 조회합니다.
                    
                    - 즉시 차단(blockEndAt이 현재 시각 이후)과 반복 차단(현재 요일/시각이 설정 구간 내)을 모두 확인합니다.
                    - 둘 중 하나라도 활성화되어 있으면 isBlocked=true를 반환합니다.
                    - 여러 차단이 동시에 활성화된 경우, blockEndsAt은 가장 늦게 끝나는 시각입니다.
                    - 차단 중이 아니면 isBlocked=false, blockEndsAt=null을 반환합니다.
                    """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "차단 상태 조회 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
                    - COMMON:4002 RequestParam 유효성 검증 실패
                    - COMMON:4003 RequestParam 타입 불일치
                    - COMMON:4004 필수 RequestParam 누락
                    """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    권한 없음
                    - COMMON:4300 가족 그룹 접근 권한이 없음
                    """
            ),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/lines/block-status")
    public ResponseEntity<BlockStatusResDto> getBlockStatus(
            @AuthenticationPrincipal AuthUserDetails auth,
            @Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam("lineId") Long lineId
    ) {
        return ResponseEntity.ok(userPolicyService.getBlockStatus(lineId, auth));
    }

}
