package org.teamsparta.addressapi.domain.address.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_address")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_address_seq")
    @SequenceGenerator(name = "user_address_seq", sequenceName = "user_address_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "recipient_name", nullable = false)
    private String recipientName;

    @Column(name = "recipient_address", nullable = false)
    private String recipientAddress;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(nullable = false)
    private boolean deleted;

    public static UserAddress create(Long userId, String recipientName, String recipientAddress, boolean isDefault) {
        UserAddress address = new UserAddress();
        address.userId = userId;
        address.recipientName = recipientName;
        address.recipientAddress = recipientAddress;
        address.isDefault = isDefault;
        address.deleted = false;
        return address;
    }

    public void update(String recipientName, String recipientAddress, Boolean isDefault) {
        if (recipientName != null) this.recipientName = recipientName;
        if (recipientAddress != null) this.recipientAddress = recipientAddress;
        if (isDefault != null) this.isDefault = isDefault;
    }

    public void softDelete() {
        this.deleted = true;
        this.isDefault = false;
    }

    public void clearDefault() {
        this.isDefault = false;
    }
}
