package com.pooli.notification.domain.entity;

import java.time.LocalDate;

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
	LocalDate createdAt;
	LocalDate deletedAt;
	LocalDate updatedAt;
	
	
}
