package com.seatrush.user;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return new MeResponse(
                Long.valueOf(jwt.getSubject()),
                jwt.getClaimAsString("username"),
                jwt.getClaimAsStringList("roles"));
    }

    public record MeResponse(Long userId, String username, List<String> roles) {
    }
}