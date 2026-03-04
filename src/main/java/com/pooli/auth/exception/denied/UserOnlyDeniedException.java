package com.pooli.auth.exception.denied;

import org.springframework.security.access.AccessDeniedException;

public class UserOnlyDeniedException extends AccessDeniedException {

	public UserOnlyDeniedException() {
		super("유저 권한이 필요합니다.");
		// TODO Auto-generated constructor stub
	}

}
