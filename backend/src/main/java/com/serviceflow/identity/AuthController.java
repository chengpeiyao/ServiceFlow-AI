package com.serviceflow.identity;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record LoginRequest(@NotBlank @Size(max=80) String username, @NotBlank @Size(max=128) String password) {}
    private final IdentityService identities;
    public AuthController(IdentityService identities) { this.identities = identities; }

    @PostMapping("/login")
    public IdentityService.LoginResult login(@RequestBody @Valid LoginRequest request) {
        return identities.login(request.username(), request.password());
    }

    @GetMapping("/me")
    public IdentityService.Actor me() { return identities.currentActor(); }

    @PostMapping("/logout")
    public void logout() { StpUtil.logout(); }
}
