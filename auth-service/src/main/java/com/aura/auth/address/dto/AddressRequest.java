package com.aura.auth.address.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Used for both create and update — the fields are identical and splitting them would produce two
 * records that have to be kept in step by hand.
 *
 * @param postalCode validated here as well as by the database CHECK constraint. The constraint is
 *                   the guarantee; this is what turns a violation into a readable 400 naming the
 *                   field, instead of a 500 from a constraint violation surfacing as a raw
 *                   DataIntegrityViolationException.
 * @param isDefault  when true, any existing default is demoted first
 */
public record AddressRequest(
    @Size(max = 100, message = "Title must be at most 100 characters")
    String title,

    @NotBlank(message = "Recipient first name is required")
    @Size(max = 100)
    String recipientFirstName,

    @NotBlank(message = "Recipient last name is required")
    @Size(max = 100)
    String recipientLastName,

    @NotBlank(message = "Phone number is required")
    String phone,

    @NotBlank(message = "Province is required")
    @Size(max = 100)
    String province,

    @NotBlank(message = "City is required")
    @Size(max = 100)
    String city,

    @NotBlank(message = "Address line is required")
    String line1,

    String line2,

    @NotBlank(message = "Postal code is required")
    @Pattern(regexp = "^[0-9]{10}$", message = "Postal code must be exactly 10 digits")
    String postalCode,

    Boolean isDefault
) {

    /**
     * Boxed and defaulted rather than a primitive {@code boolean}. A primitive record component
     * makes its JSON property effectively mandatory: omit it and Jackson cannot construct the
     * record, which surfaces as an opaque 400 "Failed to read request" naming no field. Omitting
     * this flag should mean "not the default", not "malformed request".
     */
    public AddressRequest {
        if (isDefault == null) {
            isDefault = false;
        }
    }
}
