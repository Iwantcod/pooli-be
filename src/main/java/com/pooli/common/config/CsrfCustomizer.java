package com.pooli.common.config;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.csrf.CsrfTokenRepository;

public interface CsrfCustomizer {
    void customize(HttpSecurity http, CsrfTokenRepository csrfTokenRepository) throws Exception;
}
