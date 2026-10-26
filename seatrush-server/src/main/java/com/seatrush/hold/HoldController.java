package com.seatrush.hold;

import com.seatrush.hold.HoldDtos.CreateHoldRequest;
import com.seatrush.hold.HoldDtos.HoldResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
        long userId = Long.parseLong(jwt.getSubject());
        return holdService.createHold(userId, eventId, request);
    }
}