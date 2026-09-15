package io.github.xw66.opsflow.auth;

import io.github.xw66.opsflow.common.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class UserAdminController {
    private final UserAdminService service;

    public UserAdminController(UserAdminService service) { this.service = service; }

    @GetMapping
    public ApiResponse<UserAdminService.AdminUserPage> users(
            @RequestParam(defaultValue = "") @Size(max = 100) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Role role,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(service.users(keyword, enabled, role, offset, limit));
    }

    @PutMapping("/{id}/enabled")
    public ApiResponse<AuthController.UserView> enabled(@PathVariable @Positive long id,
            @Valid @RequestBody EnabledRequest request, Authentication actor) {
        return ApiResponse.success(service.setEnabled(Long.parseLong(actor.getName()), id, request.enabled(), request.reason()));
    }

    @PutMapping("/{id}/roles")
    public ApiResponse<AuthController.UserView> roles(@PathVariable @Positive long id,
            @Valid @RequestBody RolesRequest request, Authentication actor) {
        return ApiResponse.success(service.setRoles(Long.parseLong(actor.getName()), id, request.roles(), request.reason()));
    }

    public record EnabledRequest(@NotNull Boolean enabled, @NotBlank @Size(max = 500) String reason) { }
    public record RolesRequest(@NotEmpty @Size(max = 4) Set<@NotNull Role> roles,
            @NotBlank @Size(max = 500) String reason) { }
}
