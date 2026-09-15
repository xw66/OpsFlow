package io.github.xw66.opsflow.statistics;

import io.github.xw66.opsflow.common.ApiResponse;
import io.github.xw66.opsflow.ticket.TicketActor;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/statistics")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('ADMIN','LEADER')")
public class StatisticsController {
    private final StatisticsService service;
    public StatisticsController(StatisticsService service) { this.service = service; }
    @GetMapping("/overview")
    public ApiResponse<StatisticsService.Overview> overview(Authentication actor, @RequestParam LocalDate from, @RequestParam LocalDate until) {
        return ApiResponse.success(service.overview(TicketActor.from(actor), from, until));
    }
    @GetMapping("/daily")
    public ApiResponse<List<StatisticsMapper.DailyCount>> daily(Authentication actor, @RequestParam LocalDate from, @RequestParam LocalDate until) {
        return ApiResponse.success(service.daily(TicketActor.from(actor), from, until));
    }
}
