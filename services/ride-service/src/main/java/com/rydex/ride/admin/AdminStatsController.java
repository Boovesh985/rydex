package com.rydex.ride.admin;

import com.rydex.ride.admin.AdminStats.DemandCell;
import com.rydex.ride.admin.AdminStats.Stats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ride KPIs and the demand heatmap for the admin dashboard. ADMIN only. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Ride KPIs and demand (ADMIN role)")
class AdminStatsController {

    private final AdminStats stats;

    AdminStatsController(AdminStats stats) {
        this.stats = stats;
    }

    @GetMapping("/stats")
    @Operation(summary = "Ride KPIs: rides in flight, and requests, outcomes, revenue and wait times over the last hours")
    Stats stats(@RequestParam(defaultValue = "24") @Min(1) @Max(720) int hours) {
        return stats.stats(hours);
    }

    @GetMapping("/demand")
    @Operation(summary = "Ride requests over the last hours, counted per ~500 m cell of their pickup point (heatmap)")
    List<DemandCell> demand(@RequestParam(defaultValue = "24") @Min(1) @Max(720) int hours) {
        return stats.demand(hours);
    }
}
