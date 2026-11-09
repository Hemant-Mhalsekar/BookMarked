package com.bookmarked.address;

import com.bookmarked.address.AddressService.AddressRequest;
import com.bookmarked.address.AddressService.AddressView;
import com.bookmarked.auth.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/account/addresses")
@Tag(name = "Addresses", description = "Saved delivery addresses")
@SecurityRequirement(name = "bearerAuth")
public class AddressController {

    private final AddressService addresses;

    public AddressController(AddressService addresses) {
        this.addresses = addresses;
    }

    @Operation(summary = "Saved addresses, default first")
    @GetMapping
    public List<AddressView> list(@AuthenticationPrincipal AuthUser caller) {
        return addresses.list(caller);
    }

    @Operation(summary = "Add an address (max 5; the first becomes the default)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressView create(@AuthenticationPrincipal AuthUser caller, @Valid @RequestBody AddressRequest req) {
        return addresses.create(caller, req);
    }

    @Operation(summary = "Edit an address")
    @PutMapping("/{id}")
    public AddressView update(@AuthenticationPrincipal AuthUser caller, @PathVariable Long id,
                              @Valid @RequestBody AddressRequest req) {
        return addresses.update(caller, id, req);
    }

    @Operation(summary = "Make an address the default")
    @PatchMapping("/{id}/default")
    public AddressView makeDefault(@AuthenticationPrincipal AuthUser caller, @PathVariable Long id) {
        return addresses.setDefault(caller, id);
    }

    @Operation(summary = "Delete an address (past orders keep their copy)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser caller, @PathVariable Long id) {
        addresses.delete(caller, id);
        return ResponseEntity.noContent().build();
    }
}
