package org.teamsparta.addressapi.global.exception;

public class AddressNotFoundException extends RuntimeException {

    public AddressNotFoundException(Long id) {
        super("Address not found: " + id);
    }
}
