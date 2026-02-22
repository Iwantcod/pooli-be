package com.pooli.family.controller;

import com.pooli.family.domain.dto.response.SharedDataThresholdResDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Family", description = "가족 관련 API")
@RestController
@RequestMapping("/api/families")
public class FamilyController {

    @Operation(
            summary = "가족 공유 데이터 사용량 알람 임계치 조회",
            description = "가족 식별자를 통해 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "데이터가 존재하지 않음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/shared-data-limit")
    public ResponseEntity<SharedDataThresholdResDto> getSharedDataLimit(
            @Parameter(description = "가족 식별자", example = "1")
            @RequestParam Long familyId
    ) {
        SharedDataThresholdResDto result = SharedDataThresholdResDto.builder().build();
        return ResponseEntity.ok(result);
    }
}