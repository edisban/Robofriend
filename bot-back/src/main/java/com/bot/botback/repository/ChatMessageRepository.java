package com.bot.botback.repository;

import com.bot.botback.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByUserIdOrderByCreatedAtAsc(Long userId);
    List<ChatMessage> findByUserIdAndIdGreaterThanOrderByCreatedAtAsc(Long userId, Long afterId);
    List<ChatMessage> findTop13ByUserIdOrderByCreatedAtDesc(Long userId);

    /** Removes a message and everything the user said or received after it. */
    @Transactional
    @Modifying
    @Query("delete from ChatMessage m where m.userId = :userId and m.id >= :fromId")
    int deleteFrom(@Param("userId") Long userId, @Param("fromId") Long fromId);

    @Transactional
    @Modifying
    @Query("delete from ChatMessage m where m.userId = :userId")
    int deleteAllByUser(@Param("userId") Long userId);
}
