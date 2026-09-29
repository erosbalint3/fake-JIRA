package com.fakejira.apitoken;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

    @Query("select t from ApiToken t join fetch t.user where t.hash = :hash")
    Optional<ApiToken> findByHash(@Param("hash") String hash);

    List<ApiToken> findByUserIdOrderByIdDesc(Long userId);

    @Modifying
    @Query("delete from ApiToken t where t.user.id = :userId")
    void deleteForUser(@Param("userId") Long userId);
}
