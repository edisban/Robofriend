package com.bot.botback.service;

import com.bot.botback.model.ListItem;
import com.bot.botback.repository.ListItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** List changes the model asks for, against an in-memory list. */
class ListServiceTest {

    private static final long USER = 1L;

    private final List<ListItem> stored = new ArrayList<>();
    private ListService service;

    @BeforeEach
    void setUp() {
        ListItemRepository repository = Mockito.mock(ListItemRepository.class);
        when(repository.findByUserIdAndListNameOrderByCreatedAtAsc(anyLong(), anyString())).thenAnswer(call ->
                new ArrayList<>(stored.stream().filter(i -> i.getListName().equals(call.getArgument(1))).toList()));
        when(repository.save(any(ListItem.class))).thenAnswer(call -> {
            ListItem item = call.getArgument(0);
            if (!stored.contains(item)) {
                stored.add(item);
            }
            return item;
        });
        Mockito.doAnswer(call -> stored.removeAll((Collection<?>) call.getArgument(0)))
                .when(repository).deleteAll(any());
        service = new ListService(repository);
    }

    private void apply(ListService.Op op, String list, String... items) {
        service.apply(USER, List.of(new ListService.ListOp(op, list, List.of(items))));
    }

    private ListItem item(String text) {
        return stored.stream().filter(i -> i.getText().equals(text)).findFirst().orElseThrow();
    }

    @Test
    void tickingOffIgnoresAccentsAndCase() {
        apply(ListService.Op.ADD, "shopping", "γαλα", "Ψωμί");

        apply(ListService.Op.DONE, "shopping", "Γάλα");
        apply(ListService.Op.DONE, "shopping", "ΨΩΜΙ");

        assertTrue(item("γαλα").isDone(), "\"Γάλα\" ticks off \"γαλα\"");
        assertTrue(item("Ψωμί").isDone(), "\"ΨΩΜΙ\" ticks off \"Ψωμί\"");
    }

    @Test
    void namesMatchLooselyWithinTheList() {
        apply(ListService.Op.ADD, "shopping", "2 litres milk", "eggs");
        apply(ListService.Op.ADD, "todo", "buy milk for grandma");

        apply(ListService.Op.DONE, "shopping", "milk");

        assertTrue(item("2 litres milk").isDone());
        assertFalse(item("eggs").isDone());
        assertFalse(item("buy milk for grandma").isDone(), "other lists are untouched");
    }

    @Test
    void addingSkipsOpenDuplicatesButNotDoneOnes() {
        apply(ListService.Op.ADD, "shopping", "Καφές");
        apply(ListService.Op.ADD, "shopping", "καφες", "- tea ");
        assertEquals(List.of("Καφές", "tea"), stored.stream().map(ListItem::getText).toList(),
                "same item in other accents/case isn't added twice; list markers are trimmed");

        apply(ListService.Op.DONE, "shopping", "καφές");
        apply(ListService.Op.ADD, "shopping", "Καφές");
        assertEquals(3, stored.size(), "buying it again after it was ticked off adds it back");
    }

    @Test
    void removeDeletesAndClearTicksOffEverything() {
        apply(ListService.Op.ADD, "shopping", "milk", "eggs", "bread");

        apply(ListService.Op.REMOVE, "shopping", "EGGS");
        assertEquals(List.of("milk", "bread"), stored.stream().map(ListItem::getText).toList());

        service.apply(USER, List.of(new ListService.ListOp(ListService.Op.CLEAR, "shopping", List.of())));
        assertTrue(stored.stream().allMatch(ListItem::isDone));
    }

    @Test
    void listNamesAreNormalized() {
        assertEquals("shopping list", ListService.normalizeListName("  Shopping   LIST "));
        assertNull(ListService.normalizeListName("   "));
        assertEquals(60, ListService.normalizeListName("x".repeat(80)).length());

        apply(ListService.Op.ADD, " Shopping ", "milk");
        assertEquals("shopping", stored.get(0).getListName());
    }

    @Test
    void comparisonKeyDropsAccentsCaseAndExtraSpaces() {
        assertEquals("γαλα", ListService.key("Γάλα"));
        assertEquals("γαλα", ListService.key("  ΓΆΛΑ "));
        assertEquals("cafe au lait", ListService.key("Café   au LAIT"));
    }
}
