package com.bot.botback.controller;

import com.bot.botback.dto.ListItemDoneRequest;
import com.bot.botback.dto.ListItemResponse;
import com.bot.botback.security.CurrentUser;
import com.bot.botback.service.ListService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The user's lists, for the side panel. RoboFriend itself edits them through the conversation. */
@RestController
@RequestMapping("/api/lists")
public class ListController {

    private final ListService listService;
    private final CurrentUser currentUser;

    public ListController(ListService listService, CurrentUser currentUser) {
        this.listService = listService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ListItemResponse> getItems() {
        return listService.itemsOf(currentUser.id()).stream()
                .map(item -> ListItemResponse.from(item))
                .toList();
    }

    @PatchMapping("/items/{itemId}")
    public ListItemResponse setDone(@PathVariable Long itemId, @Valid @RequestBody ListItemDoneRequest body) {
        return ListItemResponse.from(listService.setDone(currentUser.id(), itemId, body.getDone()));
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<Void> deleteItem(@PathVariable Long itemId) {
        listService.deleteItem(currentUser.id(), itemId);
        return ResponseEntity.noContent().build();
    }

    /** Removes the ticked-off items from one list. */
    @DeleteMapping("/{listName}/done")
    public Map<String, Integer> clearDone(@PathVariable String listName) {
        return Map.of("deleted", listService.clearDone(currentUser.id(), listName));
    }
}
