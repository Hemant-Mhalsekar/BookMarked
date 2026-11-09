package com.bookmarked.order;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/** The returns queue. Moving a return needs RETURN_MANAGE; paying the refund also needs REFUND_ISSUE. */
@RestController
@RequestMapping("/api/admin/returns")
@Tag(name = "Admin: returns", description = "Approve, collect, receive and refund returns")
@SecurityRequirement(name = "bearerAuth")
public class AdminReturnController {

    private final ReturnService returns;

    public AdminReturnController(ReturnService returns) {
        this.returns = returns;
    }

    @Operation(summary = "Returns, newest first (status: REQUESTED, APPROVED, PICKED_UP, RECEIVED, REFUNDED, REJECTED)")
    @GetMapping
    @PreAuthorize("hasAuthority('RETURN_MANAGE')")
    public PageResponse<ReturnService.ReturnView> list(@RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return returns.list(status, page, size);
    }

    @Operation(summary = "Approve: a pickup will be arranged")
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('RETURN_MANAGE')")
    public ReturnService.ReturnView approve(@AuthenticationPrincipal AuthUser staff, @PathVariable long id) {
        returns.approve(id, staff.id());
        return returns.get(id);
    }

    @Operation(summary = "Decline, with a reason the customer sees")
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('RETURN_MANAGE')")
    public ReturnService.ReturnView reject(@AuthenticationPrincipal AuthUser staff, @PathVariable long id,
                                           @Valid @RequestBody ReturnService.RejectRequest req) {
        returns.reject(id, req.note(), staff.id());
        return returns.get(id);
    }

    @Operation(summary = "The courier has collected the book")
    @PostMapping("/{id}/picked-up")
    @PreAuthorize("hasAuthority('RETURN_MANAGE')")
    public ReturnService.ReturnView pickedUp(@AuthenticationPrincipal AuthUser staff, @PathVariable long id) {
        returns.pickedUp(id, staff.id());
        return returns.get(id);
    }

    @Operation(summary = "The book reached the warehouse: restock it or write it off")
    @PostMapping("/{id}/received")
    @PreAuthorize("hasAuthority('RETURN_MANAGE')")
    public ReturnService.ReturnView received(@AuthenticationPrincipal AuthUser staff, @PathVariable long id,
                                             @Valid @RequestBody ReturnService.ReceiveRequest req) {
        returns.received(id, req.restock(), req.note(), staff.id());
        return returns.get(id);
    }

    @Operation(summary = "Refund: Razorpay for online orders; for COD, record the UPI reference of the payout")
    @PostMapping("/{id}/refund")
    @PreAuthorize("hasAuthority('RETURN_MANAGE') and hasAuthority('REFUND_ISSUE')")
    public ReturnService.ReturnView refund(@AuthenticationPrincipal AuthUser staff, @PathVariable long id,
                                           @Valid @RequestBody(required = false) ReturnService.RefundRequest req) {
        returns.refund(id, req == null ? null : req.upiReference(), staff.id());
        return returns.get(id);
    }
}
