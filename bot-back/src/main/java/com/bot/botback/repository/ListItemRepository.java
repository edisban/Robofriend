package com.bot.botback.repository;

import com.bot.botback.model.ListItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ListItemRepository extends JpaRepository<ListItem, Long> {
    List<ListItem> findByUserIdOrderByListNameAscDoneAscCreatedAtAsc(Long userId);
    List<ListItem> findByUserIdAndDoneFalseOrderByListNameAscCreatedAtAsc(Long userId);
    List<ListItem> findByUserIdAndListNameOrderByCreatedAtAsc(Long userId, String listName);

    @Transactional
    @Modifying
    @Query("delete from ListItem i where i.userId = :userId and i.listName = :listName and i.done = true")
    int deleteDone(@Param("userId") Long userId, @Param("listName") String listName);
}
