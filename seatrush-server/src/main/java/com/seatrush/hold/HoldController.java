package com.seatrush.hold;

import com.seatrush.hold.HoldDtos.CreateHoldRequest;
import com.seatrush.hold.HoldDtos.HoldResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HoldController {

    private final HoldService holdService;

    public HoldController(HoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping("/api/events/{eventId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse createHold(@AuthenticationPrincipal Jwt jwt, @PathVariable long eventId,
                                   @Valid @RequestBody CreateHoldRequest request) {
        return holdService.createHold(userId(jwt), eventId, request);
    }

    @GetMapping("/api/holds/{holdId}")
    public HoldResponse getHold(@AuthenticationPrincipal Jwt jwt, @PathVariable long holdId) {
        return holdService.getHold(userId(jwt), holdId);
    }

    @PostMapping("/api/holds/{holdId}/confirm")
    public HoldResponse confirmHold(@AuthenticationPrincipal Jwt jwt, @PathVariable long holdId) {
        return holdService.confirmHold(userId(jwt), holdId);
    }

    @PostMapping("/api/holds/{holdId}/cancel")
    public HoldResponse cancelHold(@AuthenticationPrincipal Jwt jwt, @PathVariable long holdId) {
        return holdService.cancelHold(userId(jwt), holdId);
    }

    /** The user id comes from the token subject, so nobody can act on behalf of someone else. */
    private static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
