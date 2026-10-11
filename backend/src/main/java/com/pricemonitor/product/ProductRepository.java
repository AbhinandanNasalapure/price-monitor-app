package com.pricemonitor.product;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    @EntityGraph(attributePaths = "snapshots")
    List<Product> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = "snapshots")
    Optional<Product> findWithSnapshotsById(Long id);
}
