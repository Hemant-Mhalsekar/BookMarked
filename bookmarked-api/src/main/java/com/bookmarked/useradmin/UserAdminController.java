package com.bookmarked.useradmin;

import com.bookmarked.audit.AuditInterceptor;
import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.ClientInfo;
import com.bookmarked.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Staff accounts (STAFF_MANAGE) and customers (USER_MANAGE). These actions write
 * detailed audit entries themselves (old and new values), so each marks the request
 * to stop the generic audit interceptor recording it a second time.
 */
@RestController
@Tag(name = "Admin: people", description = "Staff accounts and customers")
@SecurityRequirement(name = "bearerAuth")
public class UserAdminController {

    private final StaffService staff;
    private final CustomerAdminService customers;

    public UserAdminController(StaffService staff, CustomerAdminService customers) {
        this.staff = staff;
        this.customers = customers;
    }

    // ---- staff ----

    @Operation(summary = "All staff accounts")
    @GetMapping("/api/admin/staff")
    @PreAuthorize("hasAuthority('STAFF_MANAGE')")
    public List<StaffService.StaffView> staff(@AuthenticationPrincipal AuthUser caller) {
        return staff.list(caller);
    }

    @Operation(summary = "Add a staff member; they get an email to set their password")
    @PostMapping("/api/admin/staff")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('STAFF_MANAGE')")
    public StaffService.StaffView create(@AuthenticationPrincipal AuthUser caller,
                                         @Valid @RequestBody StaffService.CreateStaffRequest req, HttpServletRequest http) {
        return staff.create(caller, req, ip(http));
    }

    @Operation(summary = "Send the invite email again")
    @PostMapping("/api/admin/staff/{id}/resend-invite")
    @PreAuthorize("hasAuthority('STAFF_MANAGE')")
    public StaffService.StaffView resendInvite(@AuthenticationPrincipal AuthUser caller, @PathVariable long id,
                                               HttpServletRequest http) {
        return staff.resendInvite(caller, id, ip(http));
    }

    @Operation(summary = "Change a staff member's role (signs them out)")
    @PatchMapping("/api/admin/staff/{id}/role")
    @PreAuthorize("hasAuthority('STAFF_MANAGE')")
    public StaffService.StaffView changeRole(@AuthenticationPrincipal AuthUser caller, @PathVariable long id,
                                             @Valid @RequestBody StaffService.RoleRequest req, HttpServletRequest http) {
        return staff.changeRole(caller, id, req.role(), ip(http));
    }

    @Operation(summary = "Block a staff member (signs them out)")
    @PostMapping("/api/admin/staff/{id}/block")
    @PreAuthorize("hasAuthority('STAFF_MANAGE')")
    public StaffService.StaffView blockStaff(@AuthenticationPrincipal AuthUser caller, @PathVariable long id,
                                             HttpServletRequest http) {
        return staff.setEnabled(caller, id, false, ip(http));
    }

    @Operation(summary = "Unblock a staff member")
    @PostMapping("/api/admin/staff/{id}/unblock")
    @PreAuthorize("hasAuthority('STAFF_MANAGE')")
    public StaffService.StaffView unblockStaff(@AuthenticationPrincipal AuthUser caller, @PathVariable long id,
                                               HttpServletRequest http) {
        return staff.setEnabled(caller, id, true, ip(http));
    }

    @Operation(summary = "Sign a staff member out everywhere")
    @PostMapping("/api/admin/staff/{id}/sign-out")
    @PreAuthorize("hasAuthority('STAFF_MANAGE')")
    public StaffService.StaffView signOutStaff(@AuthenticationPrincipal AuthUser caller, @PathVariable long id,
                                               HttpServletRequest http) {
        return staff.signOut(caller, id, ip(http));
    }

    // ---- customers ----

    @Operation(summary = "Search customers by name, email or phone (status: ALL, ACTIVE, BLOCKED, DELETED)")
    @GetMapping("/api/admin/customers")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public PageResponse<CustomerAdminService.CustomerRow> customers(@RequestParam(required = false) String q,
                                                                    @RequestParam(defaultValue = "ALL") String status,
                                                                    @RequestParam(defaultValue = "0") int page,
                                                                    @RequestParam(defaultValue = "20") int size) {
        return customers.search(q, status, page, size);
    }

    @Operation(summary = "A customer's profile, orders and recent logins")
    @GetMapping("/api/admin/customers/{id}")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public CustomerAdminService.CustomerDetail customer(@PathVariable long id) {
        return customers.detail(id);
    }

    @Operation(summary = "Block a customer (signs them out)")
    @PostMapping("/api/admin/customers/{id}/block")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public CustomerAdminService.CustomerDetail blockCustomer(@AuthenticationPrincipal AuthUser caller,
                                                             @PathVariable long id, HttpServletRequest http) {
        return customers.setEnabled(caller, id, false, ip(http));
    }

    @Operation(summary = "Unblock a customer")
    @PostMapping("/api/admin/customers/{id}/unblock")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public CustomerAdminService.CustomerDetail unblockCustomer(@AuthenticationPrincipal AuthUser caller,
                                                               @PathVariable long id, HttpServletRequest http) {
        return customers.setEnabled(caller, id, true, ip(http));
    }

    @Operation(summary = "Sign a customer out everywhere")
    @PostMapping("/api/admin/customers/{id}/sign-out")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public CustomerAdminService.CustomerDetail signOutCustomer(@AuthenticationPrincipal AuthUser caller,
                                                               @PathVariable long id, HttpServletRequest http) {
        return customers.signOut(caller, id, ip(http));
    }

    /** The client IP, and a note to the audit interceptor that this action is already recorded. */
    private static String ip(HttpServletRequest http) {
        http.setAttribute(AuditInterceptor.RECORDED, Boolean.TRUE);
        return ClientInfo.from(http).ipAddress();
    }
}
