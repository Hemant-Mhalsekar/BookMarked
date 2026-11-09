package com.bookmarked.order;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.PageResponse;
import com.bookmarked.order.OrderEnums.FulfillmentStatus;
import com.bookmarked.order.OrderEnums.OrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order handling for staff. /api/admin/** already requires a staff account;
 * reading needs ORDER_READ, changes need ORDER_UPDATE, refunds need REFUND_ISSUE.
 * Every action answers with the refreshed order.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin: orders", description = "Fulfilment, cancellations and refunds")
@SecurityRequirement(name = "bearerAuth")
public class AdminOrderController {

    private final AdminOrderQueryService queries;
    private final PurchaseOrderRepository orders;
    private final FulfilmentService fulfilment;
    private final CancellationService cancellations;
    private final RefundService refunds;
    private final InvoiceService invoices;

    public AdminOrderController(AdminOrderQueryService queries, PurchaseOrderRepository orders,
                                FulfilmentService fulfilment, CancellationService cancellations,
                                RefundService refunds, InvoiceService invoices) {
        this.queries = queries;
        this.orders = orders;
        this.fulfilment = fulfilment;
        this.cancellations = cancellations;
        this.refunds = refunds;
        this.invoices = invoices;
    }

    @Operation(summary = "Search orders by number, customer email or phone; filter by order or item status")
    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('ORDER_READ')")
    public PageResponse<AdminOrderQueryService.AdminOrderRow> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) FulfillmentStatus itemStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.search(q, status, itemStatus, page, size);
    }

    @Operation(summary = "One order with customer, items, timelines, shipments and refunds")
    @GetMapping("/orders/{orderNumber}")
    @PreAuthorize("hasAuthority('ORDER_READ')")
    public AdminOrderQueryService.AdminOrderDetail detail(@PathVariable String orderNumber) {
        return queries.detail(orderNumber);
    }

    @Operation(summary = "Items PLACED → PROCESSING (being packed)")
    @PostMapping("/orders/{orderNumber}/process")
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public AdminOrderQueryService.AdminOrderDetail process(@AuthenticationPrincipal AuthUser staff,
                                                           @PathVariable String orderNumber,
                                                           @Valid @RequestBody FulfilmentService.ItemsRequest req) {
        fulfilment.markProcessing(id(orderNumber), req.itemIds(), staff.id());
        return queries.detail(orderNumber);
    }

    @Operation(summary = "Items PROCESSING → SHIPPED, with courier, tracking number and expected date")
    @PostMapping("/orders/{orderNumber}/ship")
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public AdminOrderQueryService.AdminOrderDetail ship(@AuthenticationPrincipal AuthUser staff,
                                                        @PathVariable String orderNumber,
                                                        @Valid @RequestBody FulfilmentService.ShipRequest req) {
        fulfilment.ship(id(orderNumber), req, staff.id());
        return queries.detail(orderNumber);
    }

    @Operation(summary = "Items SHIPPED → DELIVERED")
    @PostMapping("/orders/{orderNumber}/deliver")
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public AdminOrderQueryService.AdminOrderDetail deliver(@AuthenticationPrincipal AuthUser staff,
                                                           @PathVariable String orderNumber,
                                                           @Valid @RequestBody FulfilmentService.ItemsRequest req) {
        fulfilment.markDelivered(id(orderNumber), req.itemIds(), staff.id());
        return queries.detail(orderNumber);
    }

    @Operation(summary = "Record that the courier collected the Cash on Delivery payment")
    @PostMapping("/orders/{orderNumber}/cod-collected")
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public AdminOrderQueryService.AdminOrderDetail codCollected(@AuthenticationPrincipal AuthUser staff,
                                                                @PathVariable String orderNumber) {
        fulfilment.markCodCollected(id(orderNumber), staff.id());
        return queries.detail(orderNumber);
    }

    @Operation(summary = "Cancel items that haven't shipped, refunding online payments")
    @PostMapping("/orders/{orderNumber}/cancel")
    @PreAuthorize("hasAuthority('ORDER_UPDATE') and hasAuthority('REFUND_ISSUE')")
    public AdminOrderQueryService.AdminOrderDetail cancel(@AuthenticationPrincipal AuthUser staff,
                                                          @PathVariable String orderNumber,
                                                          @Valid @RequestBody CancellationService.CancelRequest req) {
        if (req.reason() == null || req.reason().isBlank()) {
            throw ApiException.badRequest("REASON_REQUIRED", "Tell the customer why the order is being cancelled.");
        }
        cancellations.cancel(id(orderNumber), req.itemIds(), req.reason(), staff.id());
        return queries.detail(orderNumber);
    }

    @Operation(summary = "Invoice PDF")
    @GetMapping("/orders/{orderNumber}/invoice")
    @PreAuthorize("hasAuthority('ORDER_READ')")
    public ResponseEntity<byte[]> invoice(@PathVariable String orderNumber) {
        return OrderController.pdf(invoices.forOrder(orderNumber, null));
    }

    // ---- refunds ----

    @Operation(summary = "Refunds, newest first (status: PENDING, PROCESSED, FAILED)")
    @GetMapping("/refunds")
    @PreAuthorize("hasAuthority('REFUND_ISSUE')")
    public PageResponse<RefundService.RefundView> refunds(@RequestParam(required = false) String status,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return refunds.list(status, page, size);
    }

    @Operation(summary = "Retry a failed refund")
    @PostMapping("/refunds/{id}/retry")
    @PreAuthorize("hasAuthority('REFUND_ISSUE')")
    public RefundService.RefundView retry(@PathVariable long id) {
        return refunds.retry(id);
    }

    private Long id(String orderNumber) {
        return orders.findIdByOrderNumber(orderNumber)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found."));
    }
}
