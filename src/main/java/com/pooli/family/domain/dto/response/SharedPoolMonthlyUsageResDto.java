package com.pooli.family.domain.dto.response;

import java.util.List;

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
@Schema(description = "메인 대시보드 공유풀 사용량 응답 DTO")
public class SharedPoolMonthlyUsageResDto {
	
	@Schema(description = "가족 공유데이터 총량", example = "30000000")
	private Long sharedPoolTotalData;
	
	@Schema(description = "구성원 별 사용량 List", example = "30000000")
	private List<MemberUsageDto> membersUsageList;

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(description = "구성원 별 사용량 DTO")
    public static class MemberUsageDto {
	
		@Schema(description = "사용자 이름", example = "김철수")
		private String userName;
		
		@Schema(description = "전화 번호", example = "010-1111-1111")
		private String phoneNumber;
		
		@Schema(description = "당월 공유풀 총 사용량", example = "5000")
		private Long monthlySharedPoolUsage;
    }

}
