package com.pooli.auth.service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@Getter
@Builder
public class AuthUserDetails implements UserDetails {

    private final Long userId;
    private final String userName;
    private final String email;
    private final String password;
    private final Long lineId;
    private final List<GrantedAuthority> authorities;

    public static List<GrantedAuthority> toAuthorities(List<String> roleNames) {
        return roleNames.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(role -> !role.isEmpty())
            .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toList());
    }

    public List<String> getRoleNames() {
        return authorities.stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toList());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public String getPassword() {
        return password;
    }

    public AuthUserDetails withoutPassword() {
        return AuthUserDetails.builder()
            .userId(userId)
            .userName(userName)
            .email(email)
            .password(null)
            .lineId(lineId)
            .authorities(authorities)
            .build();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
