package com.pooli.policy.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pooli.auth.authz.MethodAuthz;
import com.pooli.policy.domain.dto.response.RepeatBlockRehydrateAllResDto;
import com.pooli.policy.service.AdminPolicyService;
import org.springframework.boot.test.context.TestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

@WebMvcTest(controllers = AdminPolicyController.class)
@Import({MethodAuthz.class, AdminPolicyControllerWebMvcTest.MethodSecurityTestConfig.class})
class AdminPolicyControllerWebMvcTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminPolicyService adminPolicyService;

    @Test
    @DisplayName("관리자 권한이면 repeat block Redis 전체 재적재 API 호출이 성공한다")
    @WithMockUser(roles = "ADMIN")
    void rehydrateAllRepeatBlocksToRedis_asAdmin_returnsOk() throws Exception {
        // given
        RepeatBlockRehydrateAllResDto response = RepeatBlockRehydrateAllResDto.builder()
                .totalLineCount(10)
                .successCount(9)
                .failureCount(1)
                .failedLineIds(java.util.List.of(400L))
                .build();
        when(adminPolicyService.rehydrateAllRepeatBlocksToRedis()).thenReturn(response);

        // when & then
        mockMvc.perform(post("/api/admin/policies/repeat-blocks/rehydrate-all").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLineCount").value(10))
                .andExpect(jsonPath("$.successCount").value(9))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.failedLineIds[0]").value(400));

        verify(adminPolicyService).rehydrateAllRepeatBlocksToRedis();
    }

    @Test
    @DisplayName("비관리자 권한이면 repeat block Redis 전체 재적재 API 호출이 거부된다")
    @WithMockUser(roles = "USER")
    void rehydrateAllRepeatBlocksToRedis_asUser_returnsForbidden() throws Exception {
        // when & then
        mockMvc.perform(post("/api/admin/policies/repeat-blocks/rehydrate-all").with(csrf()))
                .andExpect(status().isForbidden());

        verify(adminPolicyService, never()).rehydrateAllRepeatBlocksToRedis();
    }
}
