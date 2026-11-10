package com.bookmarked.inventory;

import com.bookmarked.audit.AuditInterceptor;
import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.ClientInfo;
import com.bookmarked.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/inventory")
@Tag(name = "Admin: inventory", description = "Stock levels, adjustments and the stock ledger")
@SecurityRequirement(name = "bearerAuth")
public class InventoryController {

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @Operation(summary = "Printed editions with stock, reserved and sold in 30 days (filter: ALL, LOW, OUT)")
    @GetMapping
    @PreAuthorize("hasAuthority('BOOK_READ')")
    public InventoryService.InventoryPage list(@RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "ALL") String filter,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "25") int size) {
        return inventory.list(q, filter, page, size);
    }

    @Operation(summary = "Restock or correct the count of one edition")
    @PostMapping("/{variantId}/adjust")
    @PreAuthorize("hasAuthority('INVENTORY_UPDATE')")
    public InventoryService.StockRow adjust(@AuthenticationPrincipal AuthUser caller, @PathVariable long variantId,
                                            @Valid @RequestBody InventoryService.AdjustRequest req,
                                            HttpServletRequest http) {
        http.setAttribute(AuditInterceptor.RECORDED, Boolean.TRUE);
        return inventory.adjust(caller, variantId, req, ClientInfo.from(http).ipAddress());
    }

    @Operation(summary = "Stock ledger for one edition, newest first")
    @GetMapping("/{variantId}/movements")
    @PreAuthorize("hasAuthority('BOOK_READ')")
    public PageResponse<InventoryService.Movement> movements(@PathVariable long variantId,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return inventory.movements(variantId, page, size);
    }
}
