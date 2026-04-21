package org.teamsparta.logisticsapi.domain.logistics.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;
import org.teamsparta.logisticsapi.global.enums.ShipmentStatus;

import java.time.ZonedDateTime;

@Table(name = "shipment")
@Entity
@Getter
@DynamicInsert
@DynamicUpdate
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    Long orderId;

    @Column(name = "tracking_number")
    String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    ShipmentStatus status;

    @Column(name = "carrier")
    String carrier;

    @Column(name = "recipient_name")
    String recipientName;

    @Column(name = "recipient_address")
    String recipientAddress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    ZonedDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    ZonedDateTime updatedAt;

    public static Shipment create(Long orderId, String recipientName, String recipientAddress) {
        Shipment shipment = new Shipment();
        shipment.orderId = orderId;
        shipment.status = ShipmentStatus.READY;
        shipment.recipientName = recipientName;
        shipment.recipientAddress = recipientAddress;
        return shipment;
    }

    public void changeStatus(ShipmentStatus nextStatus) {
        this.status.validateTransitionTo(nextStatus);
        this.status = nextStatus;
    }

    public void assignTracking(String trackingNumber, String carrier) {
        this.trackingNumber = trackingNumber;
        this.carrier = carrier;
    }
}
