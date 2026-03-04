package com.pooli.family.domain.dto.response;

import java.util.List;

import com.pooli.family.domain.dto.response.FamilyMembersResDto.FamilyMemberDto;
import com.pooli.family.domain.enums.FamilyRole;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "가족 구성원 DTO")
public class FamilyMemberSummaryResDto {
	
	@Schema(description = "가족 식별자", example = "1")
    private Integer familyId;
	
    @Schema(description = "가족 구성원 요약 정보 목록")
    private List<FamilyMemberSummaryDto> members;
	
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	@Schema(description = "가족 구성원 DTO")
	public static class FamilyMemberSummaryDto {
		
		@Schema(description = "회선 아이디", example = "1")
		private Long lineId;
		
		@Schema(description = "유저 아이디", example = "1")
		private Long userId;

		@Schema(description = "유저 이름", example = "김철수")
		private String userName;
		
		@Schema(description = "전화 번호", example = "1")
		private String phone;
		
		@Schema(description = "유저 아이디", example = "1")
		private String role;
		 
	}

}
