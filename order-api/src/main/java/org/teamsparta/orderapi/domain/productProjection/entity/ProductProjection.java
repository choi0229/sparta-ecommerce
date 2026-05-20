package org.teamsparta.orderapi.domain.productProjection.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.teamsparta.orderapi.domain.productProjection.event.dto.ProductVariantResult;
import org.teamsparta.orderapi.global.enums.ProductVariantStatus;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Map;

@Table(name = "product_projection")
@Entity
@Getter
@DynamicInsert
@DynamicUpdate
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class ProductProjection {

    @Id
    @Column(name = "sku", nullable = false, length = 80)
    String sku;

    @Column(name = "variant_id", nullable = false, unique = true)
    Long variantId;

    @Column(name = "product_id", nullable = false)
    Long productId;

    @Column(name = "price", nullable = false)
    BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    ProductVariantStatus status;

    @Column(name = "product_name", nullable = false)
    String productName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "option_json", columnDefinition = "jsonb")
    Map<String, Object> optionJson;

    @Column(name = "category_path")
    String categoryPath;

    @Column(name = "updated_at")
    @UpdateTimestamp
    ZonedDateTime updatedAt;

    public static ProductProjection from(ProductVariantResult request){
        return ProductProjection.builder()
                .variantId(request.variantId())
                .productId(request.productId())
                .sku(request.sku())
                .price(request.price())
                .status(ProductVariantStatus.valueOf(request.status()))
                .productName(request.productName())
                .optionJson(request.optionJson())
                .categoryPath(request.categoryPath())
                .build();
    }

    public void update(ProductVariantResult request){
        this.status = ProductVariantStatus.valueOf(request.status());
        this.productName = request.productName();
        this.price = request.price();
        this.optionJson = request.optionJson();
        this.categoryPath = request.categoryPath();
    }
}
