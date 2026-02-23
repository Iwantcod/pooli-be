package com.pooli.line.controller;

import com.pooli.line.domain.dto.request.UpdateIndividualThresholdReqDto;
import com.pooli.line.domain.dto.response.IndividualThresholdResDto;
import com.pooli.line.domain.dto.response.LineSimpleResDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Line", description = "회선 관련 API")
@RestController
@RequestMapping("/api/lines")
public class LineController {

    @Operation(
            summary = "유저 회선 조회",
            description = "유저가 가지고 있는 회선들을 조회한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회선 조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "404", description = "유저 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping
    public ResponseEntity<List<LineSimpleResDto>> getLines(
            // 아래 주석처리처럼 로그인 User를 가져와서 userId를 가져오는 게 안전하다고 함.
            // @AuthenticationPrincipal LoginUser loginUser
    ) {
        List<LineSimpleResDto> response = List.of(
                LineSimpleResDto.builder()
                        .lineId(1L)
                        .phoneNumber("010-0000-0000")
                        .build()
        );

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "유저 회선별 개인 임계치 조회",
            description = "유저 회선별 설정된 개인 임계치를 조회한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "임계치 조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "404", description = "회선 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/thresholds")
    public ResponseEntity<IndividualThresholdResDto> getIndividualThreshold(
            @Parameter(description = "회선 ID", example = "1")
            @RequestParam Integer lineId
    ) {

        IndividualThresholdResDto response = IndividualThresholdResDto.builder()
                .individualThreshold(3000L)
                .isThresholdActive(true)
                .build();

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/thresholds")
    public ResponseEntity<Void> updateIndividualThreshold(
            @RequestBody UpdateIndividualThresholdReqDto request
    ) {

        return ResponseEntity.ok().build();
    }
}
