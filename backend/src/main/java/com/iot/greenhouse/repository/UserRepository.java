package com.iot.greenhouse.repository;

import com.iot.greenhouse.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmailIgnoreCase(String email);

    @Query("select u from User u left join fetch u.roles r where lower(u.username) = lower(:login) or lower(u.email) = lower(:login)")
    Optional<User> findByUsernameOrEmail(@Param("login") String login);

    /** Single-query variant used on every authenticated request to avoid an N+1. */
    @Query("select distinct u from User u left join fetch u.roles where u.id = :id")
    Optional<User> findByIdWithRoles(@Param("id") Long id);

    /** Fetch graph keeps this to two queries instead of one per row. */
    @Override
    @EntityGraph(attributePaths = "roles")
    Page<User> findAll(Specification<User> spec, Pageable pageable);
}
