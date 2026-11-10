package com.bot.botback.repository;

import com.bot.botback.model.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByUserIdOrderByCreatedAtAsc(Long userId);

    // Pages use the id (unique, always increasing) as the cursor, so they never skip or repeat
    // a message even when two share a timestamp.

    /** The newest messages, newest first. */
    List<ChatMessage> findByUserIdOrderByIdDesc(Long userId, Pageable page);

    /** Older messages before a cursor, newest first. */
    List<ChatMessage> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long beforeId, Pageable page);

    /** Newer messages after a cursor, oldest first (picking up what RoboFriend sent on its own). */
    List<ChatMessage> findByUserIdAndIdGreaterThanOrderByIdAsc(Long userId, Long afterId, Pageable page);
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
