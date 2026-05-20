package org.teamsparta.productapi.domain.category.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.teamsparta.productapi.domain.category.entity.Category;
import org.teamsparta.productapi.global.enums.Status;

import java.util.List;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    Boolean existsByParentId(Long id);

    List<Category> findAllByStatusOrderBySortOrderAscIdAsc(Status status);
}
