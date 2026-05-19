package org.teamsparta.addressapi.domain.address.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_address_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAddressHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "address_id")
    private Long addressId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "action_type", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private ActionType actionType;

    @Column(name = "before_recipient_name", length = 100)
    private String beforeRecipientName;

    @Column(name = "before_recipient_address", length = 255)
    private String beforeRecipientAddress;

    @Column(name = "before_is_default")
    private Boolean beforeIsDefault;

    @Column(name = "after_recipient_name", length = 100)
    private String afterRecipientName;

    @Column(name = "after_recipient_address", length = 255)
    private String afterRecipientAddress;

    @Column(name = "after_is_default")
    private Boolean afterIsDefault;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum ActionType {
        CREATE, UPDATE, DELETE
    }

    public static UserAddressHistory forCreate(UserAddress saved) {
        UserAddressHistory h = new UserAddressHistory();
        h.addressId = saved.getId();
        h.userId = saved.getUserId();
        h.actionType = ActionType.CREATE;
        h.afterRecipientName = saved.getRecipientName();
        h.afterRecipientAddress = saved.getRecipientAddress();
        h.afterIsDefault = saved.isDefault();
        h.createdAt = LocalDateTime.now();
        return h;
    }

    public static UserAddressHistory forUpdate(
            Long addressId, Long userId,
            String beforeName, String beforeAddr, boolean beforeDefault,
            String afterName, String afterAddr, boolean afterDefault) {
        UserAddressHistory h = new UserAddressHistory();
        h.addressId = addressId;
        h.userId = userId;
        h.actionType = ActionType.UPDATE;
        h.beforeRecipientName = beforeName;
        h.beforeRecipientAddress = beforeAddr;
        h.beforeIsDefault = beforeDefault;
        h.afterRecipientName = afterName;
        h.afterRecipientAddress = afterAddr;
        h.afterIsDefault = afterDefault;
        h.createdAt = LocalDateTime.now();
        return h;
    }

    public static UserAddressHistory forDelete(UserAddress address) {
        UserAddressHistory h = new UserAddressHistory();
        h.addressId = address.getId();
        h.userId = address.getUserId();
        h.actionType = ActionType.DELETE;
        h.beforeRecipientName = address.getRecipientName();
        h.beforeRecipientAddress = address.getRecipientAddress();
        h.beforeIsDefault = address.isDefault();
        h.createdAt = LocalDateTime.now();
        return h;
    }
}
