package org.teamsparta.productapi.domain.product.entity;

import jakarta.persistence.*;
import jakarta.persistence.Table;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;
import org.teamsparta.productapi.global.enums.Status;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Map;

@Table(name = "product_variant")
@Entity
@Getter
@DynamicInsert
@DynamicUpdate
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    Product product;

    @Column(name = "sku", nullable = false, unique = true, length = 80)
    String sku;

    @Column(name = "price", nullable = false)
    BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    Status status = Status.ACTIVE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "option_json", columnDefinition = "jsonb")
    Map<String, Object> optionJson;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    ZonedDateTime updatedAt;

    @Builder
    public ProductVariant(String sku, Product product, BigDecimal price, Map<String, Object> optionJson) {
        this.sku = sku;
        this.product = product;
        this.price = price;
        this.status = Status.INACTIVE;
        this.optionJson = optionJson;
    }

    public void activeStatus(){
        this.status = Status.ACTIVE;
    }
}
