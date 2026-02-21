package com.pooli.notification.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.notification.domain.dto.request.NotiSendRequestDto;

import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "notification", description = "알람 발송 API")
@RestController
@RequestMapping("/api/notifications")
public class NotiSendController {

	@PostMapping
	public ResponseEntity<List<NotiSendRequestDto>> sendAlarm() {
		return ResponseEntity.ok(List.of());
	}

}
