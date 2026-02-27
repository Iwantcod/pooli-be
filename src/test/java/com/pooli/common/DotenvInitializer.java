package com.pooli.common;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

public class DotenvInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {

        System.out.println("========== [Dotenv] 환경 변수 로딩 시작 ==========");

        try {
            // 프로젝트 루트의 .env 자동 탐색
            Dotenv dotenv = Dotenv.configure()
                    .ignoreIfMissing()
                    .systemProperties()   // 자동으로 System.setProperty 해줌
                    .load();

            dotenv.entries().forEach(entry ->
                    System.out.println("[Dotenv] Loaded Key: " + entry.getKey())
            );

            System.out.println("========== [Dotenv] 로딩 완료 ==========");

        } catch (Exception e) {
            System.err.println("[Dotenv] 로딩 중 오류 발생: " + e.getMessage());
        }
    }
}
