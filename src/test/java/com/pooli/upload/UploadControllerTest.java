package com.pooli.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.common.DotenvInitializer;
import com.pooli.common.dto.request.PresignedUrlReqDto;
import com.pooli.common.dto.request.UploadFileReqDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@ContextConfiguration(initializers = DotenvInitializer.class)
public class UploadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
//    @WithMockUser(username = "testuser", roles = {"USER"})
    void presignedUrlTest_withoutAuth() throws Exception {
        // 파일 1개짜리 리스트 생성
        UploadFileReqDto file = new UploadFileReqDto();
        file.setFileName("test.png");
        file.setContentType("image/png");

        PresignedUrlReqDto request = new PresignedUrlReqDto();
        request.setFiles(List.of(file));
        request.setDomain("QUESTION");

        mockMvc.perform(post("/api/uploads/presigned-urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()); // 인증 없이 성공 여부 확인
    }
}