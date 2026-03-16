package com.pooli.family.domain.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.ArraySchema;
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
@Schema(
        description = "가족 공유풀 사용량 조회 응답 DTO",
        example = "{\"sharedPoolTotalData\":1000000,\"membersUsageList\":[{\"userName\":\"김영희\",\"phoneNumber\":\"01090000000\",\"monthlySharedPoolUsage\":100000},{\"userName\":\"김철수\",\"phoneNumber\":\"01090000001\",\"monthlySharedPoolUsage\":150000},{\"userName\":\"김옥자\",\"phoneNumber\":\"01090000002\",\"monthlySharedPoolUsage\":300000},{\"userName\":\"김민우\",\"phoneNumber\":\"01090000003\",\"monthlySharedPoolUsage\":150000}]}"
)
public class SharedPoolMonthlyUsageResDto {

    @Schema(description = "가족 공유풀 총량(Byte)", example = "1000000")
    private Long sharedPoolTotalData;

    @ArraySchema(
            arraySchema = @Schema(description = "구성원별 당월 공유풀 사용량 목록"),
            schema = @Schema(implementation = MemberUsageDto.class)
    )
    private List<MemberUsageDto> membersUsageList;

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(description = "구성원별 당월 공유풀 사용량 DTO")
    public static class MemberUsageDto {

        @Schema(description = "사용자 이름", example = "김철수")
        private String userName;

        @Schema(description = "전화번호", example = "01090000001")
        private String phoneNumber;

        @Schema(description = "당월 공유풀 사용량(Byte)", example = "150000")
        private Long monthlySharedPoolUsage;
    }
}
