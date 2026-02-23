package com.pooli.notification.domain.dto.request;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.pooli.notification.domain.enums.AlarmCode;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "알림 생성 요청 DTO")
public class NotiSendReqDto {
   
	@Schema(description = "알림 보낼 사용자 ID", example = "1")
	private List<Long> userId; // 여러명한테 보낼걸 대비해 List 형태
	
	@Schema(description = "알림 코드", example = "policy")
	private AlarmCode alarmCode; // enum
	
	@Schema(description = "알림에서 전달하는 JSON 데이터 (없을 경우 null)", nullable = true)
	private JsonNode value;
    
}
