package com.pooli.family.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.family.domain.dto.request.UpdateVisibilityReqDto;
import com.pooli.family.domain.dto.response.FamilyMembersResDto;
import com.pooli.family.domain.dto.response.FamilyMembersSimpleResDto;
import com.pooli.family.service.FamilyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

@Tag(name = "Family", description = "가족 관련 API")
@RestController
@RequestMapping("/api/families")
@RequiredArgsConstructor
@Validated
public class FamilyController {
	
	private final FamilyService familyService;
	
	
    @Operation(
            summary = "메인 대시보드 가족별 가족 구성원 정보 조회",
            description = "메인 대시보드에서 특정 가족의 요약 정보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "가족별 가족 구성원 요약 정보 조회 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
                        잘못된 요청
                        
                        - COMMON:4002 RequestParam 유효성 검증 실패
                        - COMMON:4003 RequestParam 타입 불일치
                        - COMMON:4004 필수 RequestParam 누락
                        """
                ),
            @ApiResponse(responseCode = "404",
                    description = """
                    잘못된 요청
                    
                    - FAMILY:4401, 해당 가족 관련 정보를 찾을 수 없습니다.
                    """
                ),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/members")
    public ResponseEntity<FamilyMembersResDto> getFamilyMembers(
    	    @AuthenticationPrincipal 
    	    AuthUserDetails principal,
    	    @RequestParam(required = true, name = "familyId")
    		@NotNull
            @Parameter(description = "가족 ID", example = "1")
            Integer familyId
    ) {
    	

        return ResponseEntity.ok(familyService.getFamilyMembers(familyId, principal));
    }
    
    

    @Operation(
            summary = "가족 결합 구성원(단말) 목록 조회",
            description = "familyId에 해당하는 가족 결합에 포함된 구성원(단말) 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
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
                		권한 부족
                        
                        - COMMON:4302 권한 부족
                        """
                ),
            @ApiResponse(responseCode = "404",
			            description = """
			            리소스 없음
			            
			            - FAMILY:4401, 해당 가족 관련 정보를 찾을 수 없습니다.
			            """
			        ),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/members-simple")
    public ResponseEntity<List<FamilyMembersSimpleResDto>> getFamilyMembersSimple(
    	    @AuthenticationPrincipal AuthUserDetails principal,
    	    @RequestParam(required = true, name = "familyId") 
    	    @NotNull
            @Parameter(description = "가족 식별자", example = "1")
    	    Integer familyId
    ) {
    	
        return ResponseEntity.ok(familyService.getFamilyMembersSimple(familyId, principal));
    }
    
    

    @Operation(
            summary = "앱별 사용량 데이터 가족 공개 여부 설정 변경",
            description = "lineId에 해당하는 회선의 앱별 사용량 데이터를 "
                    + "가족에게 공개할지 여부를 변경합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 변경 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
                        잘못된 요청
                        
                        - COMMON:4001 요청 DTO 필드 유효성 검증 실패
                        """
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = """
                		권한 부족
                        
                        - COMMON:4302 권한 부족
                        """
                ),
            @ApiResponse(responseCode = "404",
			            description = """
			            리소스 없음
			            
			            - FAMILY:4401, 해당 가족 관련 정보를 찾을 수 없습니다.
			            """
			        ),
            @ApiResponse(responseCode = "404",
			            description = """
			            리소스 없음
			            
			            - FAMILY:4901, 이미 존재하는 정보입니다.
			            """
			        ),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PatchMapping("/visibility")
    public ResponseEntity<Void> updateVisibility(
    	    @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody UpdateVisibilityReqDto request
    ) {
    	
    	familyService.updateVisibility(request,principal);
        return ResponseEntity.ok().build();
    }
}