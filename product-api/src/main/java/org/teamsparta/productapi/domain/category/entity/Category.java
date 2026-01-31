package org.teamsparta.productapi.domain.category.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;
import org.teamsparta.productapi.global.enums.Status;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Table(name = "category")
@Entity
@Getter
@DynamicInsert
@DynamicUpdate
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "name", nullable = false, length = 120)
    String name;

    @JsonBackReference
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    Category parent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    Status status;

    @Column(name = "sort_order", nullable = false)
    Integer sortOrder;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    ZonedDateTime updatedAt;

    @JsonManagedReference
    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("sortOrder")
    List<Category> children = new ArrayList<>();

    @Builder
    public Category(String name, Category parent, Status status, Integer sortOrder){
        this.name = name;
        this.parent = parent;
        this.status = status;
        this.sortOrder = sortOrder != null ? sortOrder : 0;
    }

    public void update(String name, Category parent, Status status, Integer sortOrder){
        this.name = name;
        this.parent = parent;
        this.status = status;
        this.sortOrder = sortOrder;
    }

    public void updateStatus(Status status) {
        this.status = status;
    }
}
