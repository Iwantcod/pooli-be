package com.pooli.common;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

public class DotenvInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        System.out.println("========== [Dotenv] 환경 변수 로딩 시작 ==========");

        try {
            // .env 파일을 찾기 위해 여러 경로 시도
            Dotenv dotenv = Dotenv.configure()
                    .directory("../") // billing_batch 모듈 기준 상위 폴더(루트) 확인
                    .ignoreIfMissing()
                    .load();

            dotenv.entries().forEach(entry -> {
                System.setProperty(entry.getKey(), entry.getValue());
                // 변수명이 잘 찍히는지 확인 (비밀번호 제외 보안을 위해 키값만 출력 권장)
                System.out.println("[Dotenv] Loaded Key: " + entry.getKey());
            });

            System.out.println("========== [Dotenv] 로딩 완료 ==========");
        } catch (Exception e) {
            System.err.println("[Dotenv] 로딩 중 오류 발생: " + e.getMessage());
        }
    }
}