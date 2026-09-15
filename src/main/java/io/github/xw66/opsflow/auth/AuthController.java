package io.github.xw66.opsflow.auth;

import io.github.xw66.opsflow.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;

    public AuthController(AuthService service) { this.service = service; }

    @Operation(summary = "注册普通用户")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserView> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(service.register(request));
    }

    @Operation(summary = "账号密码登录")
    @PostMapping("/login")
    public ApiResponse<TokenView> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(service.login(request));
    }

    @Operation(summary = "当前用户及实时角色")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me")
    public ApiResponse<UserView> me(Authentication authentication) {
        return ApiResponse.success(service.userView(Long.parseLong(authentication.getName())));
    }

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,32}") String username,
            @NotBlank @Size(min = 10, max = 72) String password,
            @NotBlank @Size(max = 64) String displayName) { }

    public record LoginRequest(@NotBlank @Size(max = 32) String username,
            @NotBlank @Size(max = 72) String password) { }

    public record UserView(long id, String username, String displayName, boolean enabled, List<Role> roles) { }
    public record TokenView(String accessToken, String tokenType, long expiresIn) { }
}
