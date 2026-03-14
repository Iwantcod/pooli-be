package com.pooli.traffic.domain;

import com.pooli.traffic.domain.enums.TrafficLuaStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 李④컧 Lua ?ㅽ겕由쏀듃([answer, status]) ?ㅽ뻾 寃곌낵瑜??쒗쁽?섎뒗 媛?媛앹껜?낅땲??
 * ?ㅼ??ㅽ듃?덉씠?곌? event 蹂?遺꾧린 寃곗젙???????ъ슜?⑸땲??
 */
@Getter
@Builder
@AllArgsConstructor
public class TrafficLuaExecutionResult {
    private final long answer;
    private final TrafficLuaStatus status;
}
