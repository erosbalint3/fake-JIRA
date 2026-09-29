package com.fakejira.auth.oauth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    Optional<UserIdentity> findByProviderAndSubject(String provider, String subject);

    List<UserIdentity> findByUserId(Long userId);

    default List<String> providersFor(Long userId) {
        return findByUserId(userId).stream().map(UserIdentity::getProvider).sorted().toList();
    }

    @Modifying
    @Query("delete from UserIdentity i where i.user.id = :userId")
    void deleteForUser(Long userId);
}
