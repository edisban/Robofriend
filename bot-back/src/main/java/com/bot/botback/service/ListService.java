package com.bot.botback.service;

import com.bot.botback.model.ListItem;
import com.bot.botback.repository.ListItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Keeps the user's lists in sync with what they tell RoboFriend ("add milk", "I bought the eggs"...). */
@Service
public class ListService {

    private static final Logger log = LoggerFactory.getLogger(ListService.class);
    private static final int MAX_ITEM_LENGTH = 300;

    public enum Op { ADD, DONE, REMOVE, CLEAR }

    /** One change the model asked for, e.g. ADD to "shopping": ["milk", "eggs"]. */
    public record ListOp(Op op, String list, List<String> items) {
    }

    private final ListItemRepository repository;

    public ListService(ListItemRepository repository) {
        this.repository = repository;
    }

    public void apply(Long userId, List<ListOp> ops) {
        for (ListOp op : ops) {
            String list = normalizeListName(op.list());
            if (list == null) {
                continue;
            }
            try {
                switch (op.op()) {
                    case ADD -> add(userId, list, op.items());
                    case DONE -> markDone(userId, list, op.items());
                    case REMOVE -> remove(userId, list, op.items());
                    case CLEAR -> clear(userId, list);
                }
            } catch (Exception e) {
                log.error("List op {} on '{}' failed for user {}: {}", op.op(), list, userId, e.getMessage());
            }
        }
    }

    /** Open items grouped by list, for the prompt. */
    public Map<String, List<String>> openItems(Long userId) {
        return repository.findByUserIdAndDoneFalseOrderByListNameAscCreatedAtAsc(userId).stream()
                .collect(Collectors.groupingBy(item -> item.getListName(), LinkedHashMap::new,
                        Collectors.mapping(item -> item.getText(), Collectors.toList())));
    }

    private void add(Long userId, String list, List<String> items) {
        List<ListItem> existing = repository.findByUserIdAndListNameOrderByCreatedAtAsc(userId, list);
        for (String raw : items) {
            String text = clean(raw);
            if (text == null) {
                continue;
            }
            ListItem openDuplicate = existing.stream()
                    .filter(i -> !i.isDone() && key(i.getText()).equals(key(text)))
                    .findFirst().orElse(null);
            if (openDuplicate != null) {
                continue;
            }
            ListItem item = new ListItem();
            item.setUserId(userId);
            item.setListName(list);
            item.setText(text);
            existing.add(repository.save(item));
            log.info("Added '{}' to {} list of user {}", text, list, userId);
        }
    }

    private void markDone(Long userId, String list, List<String> items) {
        for (ListItem item : matching(userId, list, items)) {
            if (!item.isDone()) {
                item.setDone(true);
                item.setDoneAt(OffsetDateTime.now(ZoneOffset.UTC));
                repository.save(item);
            }
        }
    }

    private void remove(Long userId, String list, List<String> items) {
        repository.deleteAll(matching(userId, list, items));
    }

    /** "I did the shopping": everything on the list is done. */
    private void clear(Long userId, String list) {
        markDone(userId, list, List.of());
    }

    /** Items on the list matching the given names loosely ("milk" matches "2 litres milk"); all if none given. */
    private List<ListItem> matching(Long userId, String list, List<String> names) {
        List<ListItem> items = repository.findByUserIdAndListNameOrderByCreatedAtAsc(userId, list);
        if (names == null || names.isEmpty()) {
            return items;
        }
        List<String> wanted = names.stream().map(ListService::key).filter(k -> !k.isEmpty()).toList();
        return items.stream()
                .filter(i -> {
                    String k = key(i.getText());
                    return wanted.stream().anyMatch(w -> k.contains(w) || w.contains(k));
                })
                .toList();
    }

    public static String normalizeListName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            return null;
        }
        return name.length() > 60 ? name.substring(0, 60) : name;
    }

    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().replaceAll("^[-*•]\\s*", "").replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > MAX_ITEM_LENGTH ? text.substring(0, MAX_ITEM_LENGTH) : text;
    }

    /** Case- and accent-insensitive comparison key ("Γάλα" and "γαλα" match). */
    static String key(String text) {
        String decomposed = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
