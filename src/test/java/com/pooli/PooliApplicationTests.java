package com.pooli;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.pooli.common.config.TestSecurityConfig;

@SpringBootTest
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class PooliApplicationTests {

    @Test
    void contextLoads() {
    }

}
