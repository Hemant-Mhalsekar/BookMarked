package com.bookmarked.address;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.bookmarked.auth.dto.AuthDtos;
import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.CustomerOnly;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/** Saved delivery addresses: at most {@value Address#MAX_PER_USER}, exactly one default. */
@Service
public class AddressService {

    private final AddressRepository addresses;

    public AddressService(AddressRepository addresses) {
        this.addresses = addresses;
    }

    public record AddressRequest(
            @NotBlank @Pattern(regexp = AuthDtos.NAME_REGEX, message = "Enter the recipient's name") String fullName,
            @NotBlank @Pattern(regexp = AuthDtos.PHONE_REGEX, message = "Enter a valid 10-digit mobile number") String phone,
            @NotBlank(message = "Enter the address") @Size(max = 120) String line1,
            @Size(max = 120) String line2,
            @NotBlank(message = "Enter the city") @Size(min = 2, max = 60) String city,
            @NotBlank(message = "Enter the state") @Size(min = 2, max = 60) String state,
            @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Enter a 6-digit pincode") String pincode,
            @Pattern(regexp = "^(?i)(HOME|WORK|OTHER)?$", message = "Type must be HOME, WORK or OTHER") String type,
            Boolean makeDefault) {
    }

    public record AddressView(Long id, String fullName, String phone, String line1, String line2, String city,
                              String state, String pincode, String type,
                              @JsonProperty("isDefault") boolean isDefault) {
        public static AddressView from(Address a) {
            return new AddressView(a.getId(), a.getFullName(), a.getPhone(), a.getLine1(), a.getLine2(), a.getCity(),
                    a.getState(), a.getPincode(), a.getType(), a.isDefaultAddress());
        }
    }

    @Transactional(readOnly = true)
    public List<AddressView> list(AuthUser caller) {
        CustomerOnly.require(caller);
        return addresses.findByUserIdOrderByDefaultAddressDescCreatedAtDesc(caller.id()).stream()
                .map(AddressView::from).toList();
    }

    @Transactional
    public AddressView create(AuthUser caller, AddressRequest req) {
        CustomerOnly.require(caller);
        long count = addresses.countByUserId(caller.id());
        if (count >= Address.MAX_PER_USER) {
            throw ApiException.conflict("ADDRESS_LIMIT", "You can save up to " + Address.MAX_PER_USER
                    + " addresses. Delete one to add another.");
        }
        Address a = new Address(caller.id());
        apply(a, req);
        boolean makeDefault = count == 0 || Boolean.TRUE.equals(req.makeDefault());
        a = addresses.save(a);
        if (makeDefault) {
            setDefaultInternal(caller.id(), a);
        }
        return AddressView.from(a);
    }

    @Transactional
    public AddressView update(AuthUser caller, Long id, AddressRequest req) {
        CustomerOnly.require(caller);
        Address a = load(caller, id);
        apply(a, req);
        if (Boolean.TRUE.equals(req.makeDefault())) {
            setDefaultInternal(caller.id(), a);
        }
        return AddressView.from(a);
    }

    @Transactional
    public AddressView setDefault(AuthUser caller, Long id) {
        CustomerOnly.require(caller);
        Address a = load(caller, id);
        setDefaultInternal(caller.id(), a);
        return AddressView.from(a);
    }

    /** Deleting the default makes the newest remaining address the default. */
    @Transactional
    public void delete(AuthUser caller, Long id) {
        CustomerOnly.require(caller);
        Address a = load(caller, id);
        boolean wasDefault = a.isDefaultAddress();
        addresses.delete(a);
        addresses.flush();
        if (wasDefault) {
            addresses.findByUserIdOrderByDefaultAddressDescCreatedAtDesc(caller.id()).stream().findFirst()
                    .ifPresent(next -> next.setDefaultAddress(true));
        }
    }

    /** For checkout: the caller's own address, or 404 (never reveals other users' ids). */
    @Transactional(readOnly = true)
    public Address ownedBy(Long userId, Long addressId) {
        return addresses.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> ApiException.notFound("ADDRESS_NOT_FOUND", "Address not found."));
    }

    private Address load(AuthUser caller, Long id) {
        return addresses.findByIdAndUserId(id, caller.id())
                .orElseThrow(() -> ApiException.notFound("ADDRESS_NOT_FOUND", "Address not found."));
    }

    private void setDefaultInternal(Long userId, Address chosen) {
        for (Address other : addresses.findByUserIdOrderByDefaultAddressDescCreatedAtDesc(userId)) {
            other.setDefaultAddress(other.getId().equals(chosen.getId()));
        }
        chosen.setDefaultAddress(true);
    }

    private static void apply(Address a, AddressRequest req) {
        a.setFullName(req.fullName().trim());
        a.setPhone(req.phone().trim());
        a.setLine1(req.line1().trim());
        a.setLine2(StringUtils.hasText(req.line2()) ? req.line2().trim() : null);
        a.setCity(req.city().trim());
        a.setState(req.state().trim());
        a.setPincode(req.pincode().trim());
        a.setType(StringUtils.hasText(req.type()) ? req.type().trim().toUpperCase(Locale.ROOT) : "HOME");
    }
}
