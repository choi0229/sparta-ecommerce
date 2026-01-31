package org.teamsparta.productapi.domain.product.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;
import org.teamsparta.productapi.global.enums.ImageType;

import java.time.ZonedDateTime;

@Table(name = "product_image")
@Entity
@Getter
@DynamicInsert
@DynamicUpdate
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    Product product;

    @Column(name = "storage_key", nullable = false, length = 500)
    String storageKey;

    @Column(name = "url", nullable = false, length = 1000)
    String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    ImageType type = ImageType.DETAIL;

    @Column(name = "sort_order", nullable = false)
    Integer sortOrder;

    @Column(name = "is_primary", nullable = false)
    Boolean isPrimary;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    ZonedDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    ZonedDateTime updatedAt;

    @Builder
    public ProductImage(Product product, String storageKey, String url, ImageType type, Integer sortOrder, Boolean isPrimary) {
        this.product = product;
        this.storageKey = storageKey;
        this.url = url;
        this.type = type;
        this.sortOrder = sortOrder;
        this.isPrimary = isPrimary;
    }
}
