package com.aura.auth.address.dto;

import com.aura.auth.address.Address;

import java.time.OffsetDateTime;

public record AddressResponse(
    Long id,
    String title,
    String recipientFirstName,
    String recipientLastName,
    String phone,
    String province,
    String city,
    String line1,
    String line2,
    String postalCode,
    boolean isDefault,
    OffsetDateTime createdAt
) {

    public static AddressResponse from(Address address) {
        return new AddressResponse(
            address.getId(),
            address.getTitle(),
            address.getRecipientFirstName(),
            address.getRecipientLastName(),
            address.getPhone(),
            address.getProvince(),
            address.getCity(),
            address.getLine1(),
            address.getLine2(),
            address.getPostalCode(),
            address.isDefault(),
            address.getCreatedAt()
        );
    }
}
