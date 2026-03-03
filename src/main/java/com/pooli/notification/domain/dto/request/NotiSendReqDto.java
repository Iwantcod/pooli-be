package com.pooli.notification.domain.dto.request;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.NotificationTargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import javax.management.Notification;

@Getter
@Setter
@Schema(description = "알림 생성 요청 DTO")
public class NotiSendReqDto {

	@Schema(description = "직접 지정 회선 ID 목록 (DIRECT일 경우 사용)", example = "[1,2,3]")
	private List<Long> lineId;

	@NotNull
	@Schema(description = "발송 대상 타입", example = "ALL")
	private NotificationTargetType targetType;
	
	@Schema(description = "알림에서 전달하는 JSON 데이터 (없을 경우 null)", nullable = true)
	private JsonNode value;
}
