package com.pooli.policy.controller;

import com.pooli.policy.domain.dto.request.*;
import com.pooli.policy.domain.dto.response.*;
import com.pooli.policy.domain.enums.DayOfWeek;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Policy", description = "정책 API")
@RestController
@RequestMapping("/api/policies")
public class UserPolicyController {

    @Operation(
            summary = "백오피스에서 '활성화'한 전체 정책 목록 조회",
            description = "사용자 권한 필요. 가족 대표가 가족 그룹에 적용할 수 있는 활성화 정책 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping
    public ResponseEntity<List<ActivePolicyResDto>> getActivePolicies() {
        List<ActivePolicyResDto> response = List.of(
                ActivePolicyResDto.builder()
                        .policyId(1001L)
                        .policyName("야간 사용 차단")
                        .policyType("BLOCK")
                        .description("22:00부터 06:00까지 데이터 사용을 차단합니다.")
                        .build(),
                ActivePolicyResDto.builder()
                        .policyId(1002L)
                        .policyName("일일 데이터 제한")
                        .policyType("LIMIT")
                        .description("회선별 일일 데이터 사용량을 제한합니다.")
                        .build(),
                ActivePolicyResDto.builder()
                        .policyId(1003L)
                        .policyName("게임 앱 제한")
                        .policyType("APP")
                        .description("선택한 게임 앱 정책을 제한합니다.")
                        .build()
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원의 반복적 차단 정책 목록 조회",
            description = "특정 구성원의 회선 ID 필요. 해당 회선의 반복적 차단 정책 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/lines/repeat-block")
    public ResponseEntity<List<RepeatBlockPolicyResDto>> getReBlockPolicies(
            @Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam Long lineId
    ) {

        List<RepeatBlockDayResDto> days = List.of(
                RepeatBlockDayResDto.builder()
                        .dayOfWeek(DayOfWeek.MON)
                        .startAt(LocalTime.of(14, 0))
                        .endAt(LocalTime.of(18, 0))
                        .build(),
                RepeatBlockDayResDto.builder()
                        .dayOfWeek(DayOfWeek.WED)
                        .startAt(LocalTime.of(10, 0))
                        .endAt(LocalTime.of(12, 0))
                        .build()
        );

        List<RepeatBlockPolicyResDto> response = List.of(
                RepeatBlockPolicyResDto.builder()
                        .repeatBlockId(1000L)
                        .lineId(lineId)
                        .isActive(false)
                        .days(days)
                        .build()
        );

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원의 반복적 차단 정책 생성",
            description = "특정 구성원의 반복적 차단 정책을 생성합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PostMapping("/lines/repeat-block")
    public ResponseEntity<RepeatBlockPolicyResDto> createReBlockPolicies(
            @RequestBody RepeatBlockPolicyReqDto request
    ) {
        RepeatBlockPolicyResDto response = RepeatBlockPolicyResDto.builder().build();

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원의 반복적 차단 정책 수정",
            description = "특정 구성원의 반복적 차단 ID 필요. 반복적 차단 정책 목록을 수정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/lines/repeat-block")
    public ResponseEntity<RepeatBlockPolicyResDto> updateReBlockPolicies(
            @Parameter(name = "repeatBlockId", description = "반복적 차단 식별자", example = "202")
            @RequestParam Long repeatBlockId,
            @RequestBody RepeatBlockPolicyReqDto request
    ) {
        RepeatBlockPolicyResDto response = RepeatBlockPolicyResDto.builder().build();

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원의 반복적 차단 정책 삭제",
            description = "특정 구성원의 반복적 차단 ID 필요. 반복적 차단 정책 목록을 삭제합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @DeleteMapping("/lines/repeat-block")
    public ResponseEntity<RepeatBlockPolicyResDto> deleteReBlockPolicies(
            @Parameter(name = "repeatBlockId", description = "반복적 차단 식별자", example = "202")
            @RequestParam Long repeatBlockId
    ) {

        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "특정 구성원의 즉시 차단 정책 조회",
            description = "특정 구성원의 회선 ID 필요. 해당 회선의 즉시 차단 정책 내용을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/lines/immediate-block")
    public ResponseEntity<ImmediateBlockResDto> getImBlockPolicies(
            @Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam Long lineId
    ) {
        ImmediateBlockResDto response = ImmediateBlockResDto.builder()
                .lineId(lineId)
                .blockEndAt(LocalDateTime.now())
                .build();

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원의 즉시 차단 정책 수정",
            description = "특정 구성원의 회선 ID 필요. 해당 회선의 즉시 차단 정책 내용을 수정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/lines/immediate-block")
    public ResponseEntity<ImmediateBlockResDto> updateImBlockPolicies(
            @Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam Long lineId,
            @RequestBody ImmediateBlockReqDto request
    ) {

        ImmediateBlockResDto response = ImmediateBlockResDto.builder().build();

        return ResponseEntity.ok(response);
    }


    @Operation(
            summary = "특정 구성원 제한 정책 조회",
            description = "사용자 권한 필요. 특정 회선의 제한 정책 항목을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/lines/limits")
    public ResponseEntity<LimitPolicyResDto> getLimitPolicies(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId
    ) {
        return ResponseEntity.ok(LimitPolicyResDto.builder().build());
    }

    @Operation(
            summary = "특정 구성원의 하루 총 데이터 사용량 제한 제한 정책 (신규 추가 or 활성화)/비활성화",
            description = "제한 정책을 활성화하거나, 활성화했던 적이 없다면 신규 추가합니다. 혹은 비활성화합니다. 가족 대표자 권한이 필요합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "권한 없음"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/lines/days/limits/enable-toggles")
    public ResponseEntity<LimitPolicyResDto> toggleDayLimitPolicy(
            @Parameter(description = "회선 식별자", example = "1")
            @RequestParam Long lineId
    ) {
        LimitPolicyResDto answer = LimitPolicyResDto.builder().build();
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "특정 구성원의 하루 총 데이터 사용량 제한 정책 값 수정",
            description = "슬라이드로 결정된 값으로 수정합니다. 가족 대표자 권한이 필요합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "권한 없음"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/lines/days/limits")
    public ResponseEntity<LimitPolicyResDto> updateDayLimitPolicy(
            @RequestBody LimitPolicyUpdateReqDto request
    ) {
        LimitPolicyResDto answer = LimitPolicyResDto.builder().build();
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "특정 구성원의 공유풀 데이터 사용량 제한 제한 정책 (신규 추가 or 활성화)/비활성화",
            description = "제한 정책을 활성화하거나, 활성화했던 적이 없다면 신규 추가합니다. 혹은 비활성화합니다. 가족 대표자 권한이 필요합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "권한 없음"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/lines/shares/limits/enable-toggles")
    public ResponseEntity<LimitPolicyResDto> toggleShareLimitPolicy(
            @Parameter(description = "회선 식별자", example = "1")
            @RequestParam Long lineId
    ) {
        LimitPolicyResDto answer = LimitPolicyResDto.builder().build();
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "특정 구성원의 공유풀 데이터 사용량 제한 정책 값 수정",
            description = "슬라이드로 결정된 값으로 수정합니다. 가족 대표자 권한이 필요합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "권한 없음"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/lines/shares/limits")
    public ResponseEntity<LimitPolicyResDto> updateShareLimitPolicy(
            @RequestBody LimitPolicyUpdateReqDto request
    ) {
        LimitPolicyResDto answer = LimitPolicyResDto.builder().build();
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "특정 구성원 앱 별 정책 조회",
            description = "사용자 권한 필요. 특정 회선의 앱 단위 정책 목록과 PK를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/lines/apps")
    public ResponseEntity<List<AppPolicyResDto>> getAppPolicies(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId
    ) {
        List<AppPolicyResDto> response = List.of(
                AppPolicyResDto.builder()
                        .appPolicyId(7301L)
                        .appId(301)
                        .appName("YouTube")
                        .enabled(true)
                        .dailyLimitData(500L)
                        .build(),
                AppPolicyResDto.builder()
                        .appPolicyId(7302L)
                        .appId(302)
                        .appName("Instagram")
                        .enabled(true)
                        .dailyLimitData(300L)
                        .build(),
                AppPolicyResDto.builder()
                        .appPolicyId(7303L)
                        .appId(401)
                        .appName("GameX")
                        .enabled(false)
                        .dailyLimitData(0L)
                        .build()
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원 앱별 정책 신규 생성",
            description = "가족 대표자만 설정 가능합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "권한이 없음"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PostMapping("/lines/apps")
    public ResponseEntity<AppPolicyResDto> createAppPolicy(
            @RequestBody AppPolicyCreateReqDto request
    ) {
        AppPolicyResDto response = AppPolicyResDto.builder().build();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원 앱별 정책의 제한 데이터량(단위: Byte) 수정",
            description = "가족 대표자 권한 필요, value 단위: Byte"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "권한이 없음"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/lines/apps/limits")
    public ResponseEntity<AppPolicyResDto> updateAppPolicyLimit(
            @RequestBody AppDataLimitUpdateReqDto request
    ) {
        AppPolicyResDto response = AppPolicyResDto.builder().build();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원 앱별 정책의 제한 속도(단위: Kbps) 수정",
            description = "가족 대표자 권한 필요, value 단위: Kbps(ex: 1Mbps == 1000Kbps)"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "권한이 없음"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/lines/apps/speeds")
    public ResponseEntity<AppPolicyResDto> updateAppPolicySpeed(
            @RequestBody AppSpeedLimitUpdateReqDto request
    ) {
        AppPolicyResDto response = AppPolicyResDto.builder().build();
        return ResponseEntity.ok(response);
    }


    @Operation(
            summary = "구성원의 특정 앱 데이터 사용 정책 활성화/비활성화 토글 요청",
            description = "가족 대표자 권한 필요"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "권한이 없음"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/lines/apps/enable-toggles")
    public ResponseEntity<Void> toggleAppPolicyEnable(
            @Parameter(description = "앱 정책 식별자", example = "154")
            @RequestParam Long appPolicyId
    ) {
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "구성원의 특정 앱 데이터 사용 정책 삭제",
            description = "가족 대표자 권한 필요"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "권한이 없음"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @DeleteMapping("/lines/apps")
    public ResponseEntity<Void> deleteAppPolicy(
            @Parameter(description = "앱 정책 식별자", example = "154")
            @RequestParam Long appPolicyId
    ) {
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "특정 구성원에게 적용 중인 모든 정책 목록 조회(현재 적용 중인 정책)",
            description = "사용자 권한 필요. 특정 회선에 현재 적용 중인 정책 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/lines/applied")
    public ResponseEntity<List<AppliedPolicyResDto>> getAppliedPoliciesByLine(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId
    ) {
        List<AppliedPolicyResDto> response = List.of(
                AppliedPolicyResDto.builder()
                        .policyId(1001L)
                        .policyName("야간 사용 차단")
                        .policyType("BLOCK")
                        .appliedTarget("LINE")
                        .targetId(lineId)
                        .appliedAt("2026-02-20T10:10:00")
                        .build(),
                AppliedPolicyResDto.builder()
                        .policyId(1002L)
                        .policyName("일일 데이터 제한")
                        .policyType("LIMIT")
                        .appliedTarget("LINE")
                        .targetId(lineId)
                        .appliedAt("2026-02-20T10:12:00")
                        .build()
        );
        return ResponseEntity.ok(response);
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
        AppPolicyResDto response = AppPolicyResDto.builder()
                .appPolicyId(appPolicyId)
                .appId(resolveAppId(appPolicyId))
                .appName(resolveAppName(appPolicyId))
                .enabled(request.getEnabled() != null ? request.getEnabled() : Boolean.FALSE)
                .dailyLimitData(request.getDailyLimitMb() != null ? request.getDailyLimitMb() : 0L)
                .build();
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

    private Integer resolveAppId(Long appPolicyId) {
        if (Long.valueOf(7301L).equals(appPolicyId)) {
            return 301;
        }
        if (Long.valueOf(7302L).equals(appPolicyId)) {
            return 302;
        }
        if (Long.valueOf(7303L).equals(appPolicyId)) {
            return 401;
        }
        return 0;
    }

    private String resolveAppName(Long appPolicyId) {
        if (Long.valueOf(7301L).equals(appPolicyId)) {
            return "YouTube";
        }
        if (Long.valueOf(7302L).equals(appPolicyId)) {
            return "Instagram";
        }
        if (Long.valueOf(7303L).equals(appPolicyId)) {
            return "GameX";
        }
        return "Unknown";
    }

}
