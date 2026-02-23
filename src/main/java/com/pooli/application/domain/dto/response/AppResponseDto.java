package com.pooli.application.domain.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "애플리케이션 조회 응답 DTO")
public class AppResponseDto {

	 @Schema(description = "애플리케이션 ID", example = "1")
     private Long appId;

     @Schema(description = "애플리케이션 이름", example = "유튜브")
     private String appName;

     @Schema(description = "애플리케이션 생성 시간", example = "2026-02-20T14:30:00")
     private LocalDateTime createdAt;
     
     @Schema(description = "애플리케이션 사용량 제한 여부", example = "true")
     private boolean usageLimit;        
     
     @Schema(description = "애플리케이션 속도 제한 여부", example = "false")
     private boolean speedLimit;       
     
     @Schema(description = "애플리케이션 정책 예외 여부", example = "false")
     private boolean policyException;   
}
