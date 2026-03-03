package com.pooli.auth.exception.denied;

import org.springframework.security.access.AccessDeniedException;

public class AdminOnlyDeniedException extends AccessDeniedException {

	public AdminOnlyDeniedException() {
		super("관리자 권한이 필요합니다");
		// TODO Auto-generated constructor stub
	}

}
