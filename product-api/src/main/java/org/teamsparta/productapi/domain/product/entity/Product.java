package org.teamsparta.productapi.domain.product.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;
import org.teamsparta.productapi.domain.category.entity.Category;
import org.teamsparta.productapi.global.enums.Status;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Table(name = "product")
@Entity
@Getter
@DynamicInsert
@DynamicUpdate
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "name", nullable = false, length = 200)
    String name;

    @Column(name = "brand_name", nullable = false, length = 120)
    String brandName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    Status status = Status.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    Category category;

    @Column(name = "description", nullable = true)
    String description;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    ZonedDateTime updatedAt;

    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    List<ProductImage> productImages = new ArrayList<>();

    @Builder
    public Product(String name, String brandName, Category category, String description) {
        this.name = name;
        this.brandName = brandName;
        this.category = category;
        this.description = description;
    }
}
