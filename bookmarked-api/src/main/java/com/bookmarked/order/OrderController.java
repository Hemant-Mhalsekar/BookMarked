package com.bookmarked.order;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "The customer's orders")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

    private final OrderQueryService orders;

    public OrderController(OrderQueryService orders) {
        this.orders = orders;
    }

    @Operation(summary = "My orders, newest first")
    @GetMapping
    public PageResponse<OrderQueryService.OrderSummary> list(@AuthenticationPrincipal AuthUser caller,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "10") int size) {
        return orders.list(caller, page, size);
    }

    @Operation(summary = "One order (only your own)")
    @GetMapping("/{orderNumber}")
    public OrderQueryService.OrderDetail detail(@AuthenticationPrincipal AuthUser caller,
                                                @PathVariable String orderNumber) {
        return orders.detail(caller, orderNumber);
    }
}
