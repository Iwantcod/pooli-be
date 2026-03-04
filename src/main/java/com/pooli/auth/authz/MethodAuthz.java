package com.pooli.auth.authz;

import java.nio.file.AccessDeniedException;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.pooli.auth.exception.denied.AdminOnlyDeniedException;
import com.pooli.auth.exception.denied.OwnerOnlyDeniedException;
import com.pooli.auth.exception.denied.UserOnlyDeniedException;
import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;

import lombok.RequiredArgsConstructor;

@Component("authz")
@RequiredArgsConstructor
public class MethodAuthz {

	public boolean requireAdmin(Authentication authentication) {
        if (hasRole(authentication, "ROLE_ADMIN")) {
        	return true;
        }
        throw new AdminOnlyDeniedException();
    }

    public boolean requireUser(Authentication authentication) {
        if (hasRole(authentication, "ROLE_USER")) {
        	return true;
        }
        throw new UserOnlyDeniedException();

    }    
    
    public boolean requireOwner(Authentication authentication) {
        if (hasRole(authentication, "ROLE_FAMILY_OWNER")) {
        	return true;
        }
        throw new OwnerOnlyDeniedException();
    }
    
    public boolean requireAdminOrOwner(Authentication authentication) throws AccessDeniedException {
        boolean admin = hasRole(authentication, "ROLE_ADMIN");
        boolean owner = hasRole(authentication, "ROLE_FAMILY_OWNER");

        if (admin || owner) return true;

        // OR 실패: 둘 다 없음
        throw new ApplicationException(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN);
    }
	 
	 /**************/
	 /* Util Method*/
	 /**************/
	 private AuthUserDetails principal(Authentication authentication) {
         return (AuthUserDetails) authentication.getPrincipal();
     }

     private boolean hasRole(Authentication authentication, String role) {
         return authentication.getAuthorities().stream()
             .anyMatch(a -> role.equals(a.getAuthority()));
     }

     private boolean hasAnyRole(Authentication authentication, String... roles) {
         for (String role : roles) {
             if (hasRole(authentication, role)) return true;
         }
         return false;
     }
}
