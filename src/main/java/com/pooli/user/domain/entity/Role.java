package com.pooli.user.domain.entity;

import lombok.*;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Role {

    private Integer roleId;
    private String roleName;
}