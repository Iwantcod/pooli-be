package com.pooli.notification.domain.entity;

import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AlarmSetting {
	
	Long userId;
	Boolean familyAlarm;
	Boolean userAlarm;
	Boolean policyChangeAlarm;
	Boolean policyLimitAlarm;
	Boolean permissionAlarm;
	Boolean questionAlarm;
	LocalDateTime  createdAt;
	LocalDateTime  deletedAt;
	LocalDateTime  updatedAt;
	
	
}
