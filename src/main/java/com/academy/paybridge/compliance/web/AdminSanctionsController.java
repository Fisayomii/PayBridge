package com.academy.paybridge.compliance.web;

import com.academy.paybridge.compliance.service.OfacSanctionsList;
import com.academy.paybridge.compliance.service.SanctionsListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/compliance/sanctions")
@Tag(name = "10. Admin: FX and remittances")
public class AdminSanctionsController {

    private final SanctionsListService sanctions;

    public AdminSanctionsController(SanctionsListService sanctions) {
        this.sanctions = sanctions;
    }

    @GetMapping("/status")
    @Operation(summary = "OFAC SDN list: loaded or not, how many names and aliases, when, and from where")
    public OfacSanctionsList.Status status() {
        return sanctions.status();
    }

    @PostMapping("/refresh")
    @Operation(summary = "Download a fresh copy of the OFAC SDN list now (keeps the old one if the download fails)")
    public OfacSanctionsList.Status refresh() {
        return sanctions.refresh();
    }

    @GetMapping("/screen")
    @Operation(summary = "Screen one name against the OFAC list, as remittance screening does. Try 'Osama bin Laden'")
    public ScreenResult screen(@RequestParam String name) {
        return new ScreenResult(name, sanctions.isLoaded(), sanctions.screen(name));
    }

    public record ScreenResult(String name, boolean listLoaded, List<OfacSanctionsList.Hit> hits) {}
}
