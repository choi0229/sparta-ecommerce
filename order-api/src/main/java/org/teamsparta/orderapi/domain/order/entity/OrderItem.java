package org.teamsparta.orderapi.domain.order.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Map;

@Table(name = "order_item")
@Entity
@Getter
@DynamicInsert
@DynamicUpdate
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "order_id", nullable = false)
    Long orderId;

    @Column(name = "sku", nullable = false)
    String sku;

    @Column(name = "quantity", nullable = false)
    Integer quantity;

    @Column(name = "price_snapshot", nullable = false)
    BigDecimal priceSnapshot;

    @Column(name = "variant_name_snapshot", nullable = false)
    String variantNameSnapshot;

    @Column(name = "product_name_snapshot")
    String productNameSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "option_json_snapshot", columnDefinition = "jsonb")
    Map<String, Object> optionJsonSnapshot;

    @Column(name = "category_path_snapshot")
    String categoryPathSnapshot;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    ZonedDateTime createdAt;

    public static OrderItem of(Long orderId, String sku, int qty,
                               BigDecimal price, String productName, String variantName,
                               Map<String, Object> optionJson, String categoryPath) {
        return OrderItem.builder()
                .orderId(orderId)
                .sku(sku)
                .quantity(qty)
                .priceSnapshot(price)
                .productNameSnapshot(productName)
                .variantNameSnapshot(variantName)
                .optionJsonSnapshot(optionJson)
                .categoryPathSnapshot(categoryPath)
                .build();
    }
}
