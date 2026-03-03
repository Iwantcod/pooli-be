package com.pooli.notification.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "알림 설정 변경 요청 DTO")
public class AlarmSettingReqDto {

	@NotNull(message = "활성화 여부는 필수입니다.")
	@Schema(description = "활성화 여부", example = "true")
	private Boolean enabled;

}
