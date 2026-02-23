package com.pooli.family.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "가족 결합 구성원(단말) 조회 응답 DTO")
public class FamilyMembersSimpleResDto {

        @Schema(description = "회선 식별자", example = "10")
        private Long lineId;

        @Schema(description = "회원 식별자", example = "3")
        private Long userId;

        @Schema(description = "회원 이름", example = "홍길동")
        private String userName;

        @Schema(description = "전화번호", example = "01012345678")
        private String phone;

}
