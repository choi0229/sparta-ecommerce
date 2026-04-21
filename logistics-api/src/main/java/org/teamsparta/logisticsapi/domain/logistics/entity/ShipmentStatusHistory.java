package org.teamsparta.logisticsapi.domain.logistics.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.DynamicInsert;
import org.teamsparta.logisticsapi.global.enums.ShipmentStatus;

import java.time.ZonedDateTime;

@Table(name = "shipment_status_history")
@Entity
@Getter
@DynamicInsert
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShipmentStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "shipment_id", nullable = false)
    Long shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    ShipmentStatus status;

    @Column(name = "description")
    String description;

    @Column(name = "event_id")
    String eventId;

    @Column(name = "occurred_at", nullable = false)
    ZonedDateTime occurredAt;

    public static ShipmentStatusHistory record(Long shipmentId, ShipmentStatus status,
                                               String description, String eventId) {
        ShipmentStatusHistory history = new ShipmentStatusHistory();
        history.shipmentId = shipmentId;
        history.status = status;
        history.description = description;
        history.eventId = eventId;
        history.occurredAt = ZonedDateTime.now();
        return history;
    }
}
