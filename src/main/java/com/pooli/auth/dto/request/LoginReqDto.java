package com.pooli.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "로그인 요청 DTO")
public class LoginReqDto {

	@Schema(description = "이메일", example = "user@test.com")
    private String email;
	@Schema(description = "비밀번호", example = "Test1234!")
    private String password;
}
