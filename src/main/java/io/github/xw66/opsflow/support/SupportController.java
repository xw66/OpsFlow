package io.github.xw66.opsflow.support;

import io.github.xw66.opsflow.common.ApiResponse;
import io.github.xw66.opsflow.support.SupportModels.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/support")
@SecurityRequirement(name = "bearerAuth")
public class SupportController {
    private final SupportMapper data;
    private final SupportService service;

    public SupportController(SupportMapper data, SupportService service) { this.data = data; this.service = service; }

    @GetMapping("/categories")
    public ApiResponse<List<Category>> categories(@RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(data.categories(false, offset, limit));
    }

    @GetMapping("/categories/{id}/priorities")
    public ApiResponse<List<AvailablePriority>> availablePriorities(@PathVariable @Positive long id) {
        return ApiResponse.success(data.availablePriorities(id));
    }

    @GetMapping("/groups")
    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    public ApiResponse<List<Group>> groups(Authentication actor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        boolean admin = actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return ApiResponse.success(admin ? data.groups(offset, limit) : data.leaderGroups(Long.parseLong(actor.getName()), offset, limit));
    }

    @GetMapping("/groups/{id}/agents")
    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    public ApiResponse<List<Agent>> agents(@PathVariable @Positive long id, Authentication actor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        service.requireGroupAccess(id, Long.parseLong(actor.getName()));
        return ApiResponse.success(data.agents(id, offset, limit));
    }

    @GetMapping("/groups/{id}/workload")
    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ApiResponse<WorkloadPage> workload(@PathVariable @Positive long id, Authentication actor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        service.requireGroupAccess(id, Long.parseLong(actor.getName()));
        var rows = data.workload(id, offset, limit + 1);
        return ApiResponse.success(new WorkloadPage(rows.stream().limit(limit).toList(), rows.size() > limit, offset, limit));
    }

    @PutMapping("/agents/me/online")
    @PreAuthorize("hasRole('AGENT')")
    public ApiResponse<Agent> online(@Valid @RequestBody OnlineInput input, Authentication actor) {
        return ApiResponse.success(service.setOnline(Long.parseLong(actor.getName()), input));
    }

    @GetMapping("/agents/me")
    @PreAuthorize("hasRole('AGENT')")
    public ApiResponse<Agent> me(Authentication actor) {
        return ApiResponse.success(service.ownAgent(Long.parseLong(actor.getName())));
    }
}
