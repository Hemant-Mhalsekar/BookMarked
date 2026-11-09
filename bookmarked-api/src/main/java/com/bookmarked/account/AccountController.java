package com.bookmarked.account;

import com.bookmarked.account.AccountDtos.LoginHistoryDto;
import com.bookmarked.account.AccountDtos.SessionDto;
import com.bookmarked.auth.dto.AuthDtos.ChangePasswordRequest;
import com.bookmarked.auth.dto.AuthDtos.MessageResponse;
import com.bookmarked.auth.dto.AuthDtos.UserDto;
import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.RateLimitService;
import com.bookmarked.common.RateLimitService.Rule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/account")
@Tag(name = "Account", description = "The logged-in user's profile and security settings")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final AccountService accountService;
    private final RateLimitService rateLimit;

    private final AccountOverviewService overview;

    public AccountController(AccountService accountService, RateLimitService rateLimit,
                             AccountOverviewService overview) {
        this.overview = overview;
        this.accountService = accountService;
        this.rateLimit = rateLimit;
    }

    @Operation(summary = "Current user's profile, role and permissions")
    @Operation(summary = "Dashboard overview: stats, continue reading, recent orders, things to do")
    @GetMapping("/overview")
    public AccountOverviewService.Overview overview(@AuthenticationPrincipal AuthUser caller) {
        return overview.overview(caller);
    }

    @Operation(summary = "Change my name")
    @PatchMapping("/profile")
    public UserDto updateName(@AuthenticationPrincipal AuthUser caller,
                              @Valid @RequestBody AccountDtos.NameRequest req) {
        rateLimit.check(Rule.PROFILE_CHANGE, "user:" + caller.id());
        return accountService.updateName(caller, req.fullName());
    }

    @Operation(summary = "Change my mobile number (needs my password)")
    @PostMapping("/phone")
    public UserDto changePhone(@AuthenticationPrincipal AuthUser caller,
                               @Valid @RequestBody AccountDtos.PhoneRequest req) {
        rateLimit.check(Rule.PROFILE_CHANGE, "user:" + caller.id());
        return accountService.changePhone(caller, req.phone(), req.password());
    }

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal AuthUser caller) {
        return accountService.me(caller);
    }

    @Operation(summary = "Change password; signs out all other devices")
    @PostMapping("/change-password")
    public MessageResponse changePassword(@AuthenticationPrincipal AuthUser caller,
                                          @Valid @RequestBody ChangePasswordRequest req) {
        rateLimit.check(Rule.PASSWORD_CHANGE, "user:" + caller.id());
        accountService.changePassword(caller, req.currentPassword(), req.newPassword());
        return new MessageResponse("Password changed. Other devices have been signed out.");
    }

    @Operation(summary = "Active sessions (logged-in devices)")
    @GetMapping("/sessions")
    public List<SessionDto> sessions(@AuthenticationPrincipal AuthUser caller) {
        return accountService.sessions(caller);
    }

    @Operation(summary = "Log out one device")
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> revokeSession(@AuthenticationPrincipal AuthUser caller, @PathVariable Long id) {
        accountService.revokeSession(caller, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Log out all other devices")
    @DeleteMapping("/sessions")
    public MessageResponse revokeOtherSessions(@AuthenticationPrincipal AuthUser caller) {
        int count = accountService.revokeOtherSessions(caller);
        return new MessageResponse("Signed out of " + count + " other device(s).");
    }

    @Operation(summary = "Last 10 login attempts")
    @GetMapping("/login-history")
    public List<LoginHistoryDto> loginHistory(@AuthenticationPrincipal AuthUser caller) {
        return accountService.loginHistory(caller);
    }
}
