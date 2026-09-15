package io.github.xw66.opsflow.support;

import io.github.xw66.opsflow.common.ApiResponse;
import io.github.xw66.opsflow.support.SupportModels.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class SupportAdminController {
    private final SupportService service;
    private final SupportMapper data;

    public SupportAdminController(SupportService service, SupportMapper data) { this.service = service; this.data = data; }

    @GetMapping("/support-groups")
    public ApiResponse<List<Group>> groups(@RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(data.groups(offset, limit));
    }

    @PostMapping("/support-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Group> createGroup(@Valid @RequestBody GroupInput input, Authentication actor) {
        return ApiResponse.success(service.saveGroup(null, input, Long.parseLong(actor.getName())));
    }

    @PutMapping("/support-groups/{id}")
    public ApiResponse<Group> updateGroup(@PathVariable @Positive long id, @Valid @RequestBody GroupInput input, Authentication actor) {
        return ApiResponse.success(service.saveGroup(id, input, Long.parseLong(actor.getName())));
    }

    @PostMapping("/support-agents")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Agent> createAgent(@Valid @RequestBody AgentInput input, Authentication actor) {
        return ApiResponse.success(service.saveAgent(null, input, Long.parseLong(actor.getName())));
    }

    @PutMapping("/support-agents/{id}")
    public ApiResponse<Agent> updateAgent(@PathVariable @Positive long id, @Valid @RequestBody AgentInput input, Authentication actor) {
        return ApiResponse.success(service.saveAgent(id, input, Long.parseLong(actor.getName())));
    }

    @GetMapping("/ticket-categories")
    public ApiResponse<List<Category>> categories(@RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(data.categories(true, offset, limit));
    }

    @PostMapping("/ticket-categories")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Category> createCategory(@Valid @RequestBody CategoryInput input, Authentication actor) {
        return ApiResponse.success(service.saveCategory(null, input, Long.parseLong(actor.getName())));
    }

    @PutMapping("/ticket-categories/{id}")
    public ApiResponse<Category> updateCategory(@PathVariable @Positive long id, @Valid @RequestBody CategoryInput input, Authentication actor) {
        return ApiResponse.success(service.saveCategory(id, input, Long.parseLong(actor.getName())));
    }

    @GetMapping("/sla-policies")
    public ApiResponse<List<Policy>> policies(@RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(data.policies(offset, limit));
    }

    @PostMapping("/sla-policies")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Policy> createPolicy(@Valid @RequestBody PolicyInput input, Authentication actor) {
        return ApiResponse.success(service.savePolicy(null, input, Long.parseLong(actor.getName())));
    }

    @PutMapping("/sla-policies/{id}")
    public ApiResponse<Policy> updatePolicy(@PathVariable @Positive long id, @Valid @RequestBody PolicyInput input, Authentication actor) {
        return ApiResponse.success(service.savePolicy(id, input, Long.parseLong(actor.getName())));
    }
}
