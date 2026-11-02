package com.bot.botback.repository;

import com.bot.botback.model.MemoryVault;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MemoryVaultRepository extends JpaRepository<MemoryVault, Long> {

    List<MemoryVault> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<MemoryVault> findTop150ByUserIdOrderByCreatedAtDesc(Long userId);
    List<MemoryVault> findByUserIdAndFactKeyIsNotNullOrderByFactKeyAsc(Long userId);

    @Transactional
    @Modifying
    @Query("delete from MemoryVault m where m.userId = :userId")
    int deleteAllByUser(@Param("userId") Long userId);
}
