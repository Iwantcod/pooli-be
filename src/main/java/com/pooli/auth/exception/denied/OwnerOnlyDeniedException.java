package com.pooli.auth.exception.denied;

import org.springframework.security.access.AccessDeniedException;

public class OwnerOnlyDeniedException extends AccessDeniedException {

	public OwnerOnlyDeniedException() {
		super("대표자 권한이 필요합니다.");
	}

}
