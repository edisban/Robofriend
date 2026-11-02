package com.bot.botback.controller;

import com.bot.botback.dto.ListItemDoneRequest;
import com.bot.botback.model.ListItem;
import com.bot.botback.repository.ListItemRepository;
import com.bot.botback.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** The user's lists, for the side panel. RoboFriend itself edits them through the conversation. */
@RestController
@RequestMapping("/api/lists")
public class ListController {

    private final ListItemRepository repository;
    private final CurrentUser currentUser;

    public ListController(ListItemRepository repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ListItem> getItems() {
        return repository.findByUserIdOrderByListNameAscDoneAscCreatedAtAsc(currentUser.id());
    }

    @PatchMapping("/items/{itemId}")
    public ListItem setDone(@PathVariable Long itemId, @Valid @RequestBody ListItemDoneRequest body) {
        ListItem item = owned(itemId);
        boolean done = body.getDone();
        item.setDone(done);
        item.setDoneAt(done ? OffsetDateTime.now(ZoneOffset.UTC) : null);
        return repository.save(item);
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<Void> deleteItem(@PathVariable Long itemId) {
        repository.delete(owned(itemId));
        return ResponseEntity.noContent().build();
    }

    /** Removes the ticked-off items from one list. */
    @DeleteMapping("/{listName}/done")
    public Map<String, Integer> clearDone(@PathVariable String listName) {
        return Map.of("deleted", repository.deleteDone(currentUser.id(), listName));
    }

    private ListItem owned(Long itemId) {
        Long userId = currentUser.id();
        return repository.findById(itemId)
                .filter(item -> userId.equals(item.getUserId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
