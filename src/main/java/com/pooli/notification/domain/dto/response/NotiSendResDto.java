package com.pooli.notification.domain.dto.response;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.JsonNode;

import com.pooli.notification.domain.enums.AlarmCode;
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
@Schema(description = "알림 생성 응답 DTO")
public class NotiSendResDto {
	
	@Schema(description = "알림 ID", example = "1")
	private Long alarmHistoryId;
   
	@Schema(description = "회선 ID", example = "2")
	private Long lineId;
	
	@Schema(description = "알림 코드", example = "policy")
	private AlarmCode alarmCode; // enum
	
	@Schema( description = "알림에서 전달하는 JSON 데이터 (없을 경우 null)", nullable = true)
	private JsonNode value;
	
    @Schema(description = "알림 읽음 여부", example = "false")
	private Boolean isRead; 
    
    @Schema(description = "알림 생성 시간", example = "2026-02-20T14:30:00")
    private LocalDateTime createdAt;
    
}
