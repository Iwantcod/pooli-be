package com.pooli.auth.service;

import com.pooli.user.domain.entity.User;
import com.pooli.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) {
        User user = userRepository.findByEmail(email);
        if (user == null) {
            throw new UsernameNotFoundException("User not found");
        }

        List<String> roleNames = userRepository.findRoleNamesByUserId(user.getUserId());
        if (!roleNames.contains("ADMIN") && !roleNames.contains("ROLE_ADMIN")) {
            String familyRole = userRepository.findFamilyRoleByMainLineUserId(user.getUserId());
            if (familyRole != null && !familyRole.isBlank()) {
                String mappedFamilyRole = "FAMILY_" + familyRole.trim().toUpperCase();
                roleNames = new java.util.ArrayList<>(roleNames);
                roleNames.add(mappedFamilyRole);
            }
        }
        Long mainLineId = userRepository.findMainLineIdByUserId(user.getUserId());
        return AuthUserDetails.builder()
            .userId(user.getUserId())
            .userName(user.getUserName())
            .email(user.getEmail())
            .password(user.getPassword())
            .lineId(mainLineId)
            .authorities(AuthUserDetails.toAuthorities(roleNames))
            .build();
    }
}
