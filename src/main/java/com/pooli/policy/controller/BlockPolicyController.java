package com.pooli.policy.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.policy.domain.dto.request.ImmediateBlockReqDto;
import com.pooli.policy.domain.dto.request.RepeatBlockPolicyReqDto;
import com.pooli.policy.domain.dto.response.ImmediateBlockResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import com.pooli.policy.domain.enums.DayOfWeek;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "정책", description = "차단 정책 관련 API")
@RestController
@RequestMapping("/api/policies/")
public class BlockPolicyController {

    @Operation(
            summary = "특정 구성원의 반복적 차단 정책 목록 조회",
            description = "특정 구성원의 회선 ID 필요. 해당 회선의 반복적 차단 정책 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/repeat-block")
    public ResponseEntity<List<RepeatBlockPolicyResDto>> getReBlockPolicies(
    		@Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam Long lineId
            ) {
        List<RepeatBlockPolicyResDto> response = List.of(
        		RepeatBlockPolicyResDto.builder()
        				.repeatBlockId(1000L)
        				.lineId(lineId)
        				.isActive(false)
        				.startAt(1008)
        				.endAt(2037)
        				.dayOfWeek(DayOfWeek.FRI)
                        .build()
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원의 반복적 차단 정책 수정",
            description = "특정 구성원의 회선 ID, 반복적 차단 ID 필요. 해당 회선의 반복적 차단 정책 목록을 수정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/repeat-block")
    public ResponseEntity<RepeatBlockPolicyResDto> updateReBlockPolicies(
    		@Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam Long lineId,
            @Parameter(name = "repeatBlockId", description = "반복적 차단 식별자", example = "202")
            @RequestParam Long repeatBlockId,
            @RequestBody RepeatBlockPolicyReqDto request
    		) {
    	RepeatBlockPolicyResDto response = RepeatBlockPolicyResDto.builder()
        				.repeatBlockId(repeatBlockId)
        				.lineId(lineId)
        				.isActive(request.getIsActive())
        				.startAt(request.getStartAt())
        				.endAt(request.getEndAt())
        				.dayOfWeek(DayOfWeek.SUN)
                        .build();
    	
        return ResponseEntity.ok(response);
    }
    
    @Operation(
            summary = "특정 구성원의 반복적 차단 정책 삭제",
            description = "특정 구성원의 회선 ID, 반복적 차단 ID 필요. 해당 회선의 반복적 차단 정책 목록을 수정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @DeleteMapping("/repeat-block")
    public ResponseEntity<RepeatBlockPolicyResDto> deleteReBlockPolicies(
    		@Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam Long lineId,
            @Parameter(name = "repeatBlockId", description = "반복적 차단 식별자", example = "202")
            @RequestParam Long repeatBlockId
    		) {
    	RepeatBlockPolicyResDto reponse = RepeatBlockPolicyResDto.builder()
    			.lineId(lineId)
    			.repeatBlockId(repeatBlockId)
    			.deleteAt(LocalDateTime.now().toString())
    			.build();
    	
        return ResponseEntity.ok(reponse);
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
    @GetMapping("/immediate-block")
    public ResponseEntity<ImmediateBlockResDto> getImBlockPolicies(
    		@Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam Long lineId
            ) {
        ImmediateBlockResDto response = ImmediateBlockResDto.builder()
			    		.lineId(lineId)
						.blockEndAt(LocalDateTime.now().toString())
						.isBlock(true)
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
    @PatchMapping("/immediate-block")
    public ResponseEntity<ImmediateBlockResDto> updateImBlockPolicies(
    		@Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam Long lineId,
            @RequestBody ImmediateBlockReqDto request
    		) {
    	ImmediateBlockResDto response = ImmediateBlockResDto.builder()
        				.lineId(lineId)
        				.blockEndAt(LocalDateTime.now().toString())
        				.isBlock(true)
        				.updatedAt(LocalDateTime.now().toString())
                        .build();
        
        return ResponseEntity.ok(response);
    }
    
    @Operation(
    		 summary = "특정 구성원의 즉시 차단 정책 삭제(비활성화)",
             description = "특정 구성원의 회선 ID 필요. 해당 회선의 즉시 차단 정책 내용을 수정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @DeleteMapping("/immediate-block")
    public ResponseEntity<ImmediateBlockResDto> deleteImBlockPolicies(
    		@Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam Long lineId,
            @Parameter(name = "repeatBlockId", description = "반복적 차단 식별자", example = "202")
            @RequestParam Long repeatBlockId
    		) {
    	ImmediateBlockResDto reponse = ImmediateBlockResDto.builder()
    			.lineId(lineId)
				.isBlock(false)
				.updatedAt(LocalDateTime.now().toString())
                .build();
    	
        return ResponseEntity.ok(reponse);
    }
}
