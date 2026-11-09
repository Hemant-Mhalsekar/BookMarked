package com.bookmarked.order;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.CustomerOnly;
import com.bookmarked.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "The customer's orders")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

    private final OrderQueryService orders;
    private final PurchaseOrderRepository repository;
    private final CancellationService cancellations;
    private final InvoiceService invoices;
    private final ReturnService returns;

    public OrderController(OrderQueryService orders, PurchaseOrderRepository repository,
                           CancellationService cancellations, InvoiceService invoices, ReturnService returns) {
        this.returns = returns;
        this.orders = orders;
        this.repository = repository;
        this.cancellations = cancellations;
        this.invoices = invoices;
    }

    @Operation(summary = "My orders, newest first (tab: ALL, IN_PROGRESS, DELIVERED, CANCELLED)")
    @GetMapping
    public PageResponse<OrderQueryService.OrderSummary> list(@AuthenticationPrincipal AuthUser caller,
                                                             @RequestParam(defaultValue = "ALL") OrderQueryService.Tab tab,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "10") int size) {
        return orders.list(caller, tab, page, size);
    }

    @Operation(summary = "Cancel items that haven't shipped (no item ids = everything still cancellable)")
    @PostMapping("/{orderNumber}/cancel")
    public OrderQueryService.OrderDetail cancel(@AuthenticationPrincipal AuthUser caller,
                                                @PathVariable String orderNumber,
                                                @Valid @RequestBody(required = false) CancellationService.CancelRequest req) {
        CustomerOnly.require(caller);
        Long id = repository.findIdByOrderNumberAndUserId(orderNumber, caller.id())
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found."));
        cancellations.cancel(id, req == null ? null : req.itemIds(), req == null ? null : req.reason(), null);
        return orders.detail(caller, orderNumber);
    }

    @Operation(summary = "Return a delivered book within 7 days (refund only)")
    @PostMapping("/{orderNumber}/items/{itemId}/return")
    public OrderQueryService.OrderDetail requestReturn(@AuthenticationPrincipal AuthUser caller,
                                                       @PathVariable String orderNumber, @PathVariable Long itemId,
                                                       @Valid @RequestBody ReturnService.ReturnRequest req) {
        CustomerOnly.require(caller);
        Long id = repository.findIdByOrderNumberAndUserId(orderNumber, caller.id())
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found."));
        returns.request(id, itemId, req);
        return orders.detail(caller, orderNumber);
    }

    @Operation(summary = "Download the invoice (PDF)")
    @GetMapping("/{orderNumber}/invoice")
    public ResponseEntity<byte[]> invoice(@AuthenticationPrincipal AuthUser caller, @PathVariable String orderNumber) {
        CustomerOnly.require(caller);
        return pdf(invoices.forOrder(orderNumber, caller.id()));
    }

    static ResponseEntity<byte[]> pdf(InvoiceService.Invoice invoice) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(invoice.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(invoice.pdf());
    }

    @Operation(summary = "One order (only your own)")
    @GetMapping("/{orderNumber}")
    public OrderQueryService.OrderDetail detail(@AuthenticationPrincipal AuthUser caller,
                                                @PathVariable String orderNumber) {
        return orders.detail(caller, orderNumber);
    }
}
