package com.pooli.family.domain.dto.response;

import com.pooli.family.domain.enums.FamilyRole;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;

@Getter
@Builder
@Schema(description = "가족 구성원 조회 응답 DTO")
public class FamilyMembersResDto {

    @Schema(description = "상세 페이지 열람 권한 활성화 여부", example = "true")
    private Boolean isEnable;

    @Schema(description = "가족 식별자", example = "1")
    private Integer familyId;

    @Schema(description = "가족 공유풀 총량", example = "10000000")
    private Long sharedPoolTotalData;

    @Schema(description = "가족 구성원 목록")
    private List<FamilyMemberDto> members;


    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(description = "가족 구성원 DTO")
    public static class FamilyMemberDto {

        @Schema(description = "회원 식별자", example = "100")
        private Integer userId;

        @Schema(description = "회선 식별자", example = "10")
        private Integer lineId;

        @Schema(description = "요금제 식별자", example = "3")
        private Integer planId;

        @Schema(description = "회원 이름", example = "홍길동")
        private String userName;

        @Schema(description = "전화번호", example = "010-1234-5678")
        private String phone;

        @Schema(description = "요금제명", example = "5G 프리미엄")
        private String planName;

        @Schema(description = "기본 제공 데이터 잔량(bite)", example = "5000")
        private Long remainingData;

        @Schema(description = "기본 제공 데이터량(MB)", example = "10000")
        private Long basicDataAmount;

        @Schema(description = "가족 역할 (OWNER / MEMBER)", example = "OWNER")
        private FamilyRole role;

        @Schema(description = "사용 가능한 공유풀 데이터 총량(bite)", example = "120")
        private Long sharedPoolTotalAmount;

        @Schema(description = "사용 가능한 공유풀 데이터 잔량(bite)", example = "12000")
        private Long sharedPoolRemainingAmount;

    }
}