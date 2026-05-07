package org.teamsparta.logisticsapi.domain.logistics.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.DynamicInsert;

import java.time.ZonedDateTime;

@Table(name = "shipment_address_history")
@Entity
@Getter
@DynamicInsert
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShipmentAddressHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "shipment_id", nullable = false)
    Long shipmentId;

    @Column(name = "previous_recipient_name")
    String previousRecipientName;

    @Column(name = "previous_recipient_address")
    String previousRecipientAddress;

    @Column(name = "new_recipient_name")
    String newRecipientName;

    @Column(name = "new_recipient_address")
    String newRecipientAddress;

    @Column(name = "changed_at", nullable = false)
    ZonedDateTime changedAt;

    public static ShipmentAddressHistory record(Long shipmentId,
                                                String previousRecipientName,
                                                String previousRecipientAddress,
                                                String newRecipientName,
                                                String newRecipientAddress) {
        ShipmentAddressHistory history = new ShipmentAddressHistory();
        history.shipmentId = shipmentId;
        history.previousRecipientName = previousRecipientName;
        history.previousRecipientAddress = previousRecipientAddress;
        history.newRecipientName = newRecipientName;
        history.newRecipientAddress = newRecipientAddress;
        history.changedAt = ZonedDateTime.now();
        return history;
    }
}
