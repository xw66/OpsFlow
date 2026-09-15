package io.github.xw66.opsflow.system;

import io.github.xw66.opsflow.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    @Operation(summary = "查看服务信息")
    @GetMapping("/info")
    public ApiResponse<ServiceInfo> info() {
        return ApiResponse.success(new ServiceInfo("OpsFlow", "智能工单与 SLA 管理平台"));
    }

    public record ServiceInfo(String name, String description) { }
}
