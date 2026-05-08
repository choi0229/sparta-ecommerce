package org.teamsparta.orderapi.domain.order.client;

public interface AddressServiceClient {

    AddressInfo findById(Long addressId);

    record AddressInfo(String recipientName, String recipientAddress) {}
}
