package com.pooli.notification.domain.entity;

import java.time.LocalDate;

import com.pooli.notification.domain.enums.AlarmCode;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AlarmHistory {

	Long alarm_historyId;
	Long userId;
	AlarmCode alarmCode;
	String value;
	LocalDate createdAt;
	LocalDate deletedAt;
	LocalDate readAt;
}
