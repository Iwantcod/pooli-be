package com.pooli.notification.domain.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.pooli.notification.domain.enums.AlarmCode;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "알림 생성 요청 DTO")
public class NotiSendRequestDto {
   
	@Schema(description = "알림 보낼 사용자 ID", example = "1")
	private Long userId;
	
	@Schema(description = "알림 코드", example = "policy")
	private AlarmCode alarmCode; // enum
	
	@Schema( description = "알림에서 전달하는 JSON 데이터 (없을 경우 null)", nullable = true)
	private JsonNode value;
    
}
