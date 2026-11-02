package com.bot.botback.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplySplitterTest {

    @Test
    void streamsReplyAndHidesMetaEvenWhenMarkerIsSplitAcrossChunks() {
        List<String> shown = new ArrayList<>();
        ReplySplitter splitter = new ReplySplitter(shown::add);

        for (String chunk : List.of("Hey! How", " was your", " day?\n###ME", "TA###\n{\"factsToRemember\":[]}")) {
            splitter.feed(chunk);
        }
        splitter.finish();

        String visible = String.join("", shown);
        assertEquals("Hey! How was your day?", visible.trim());
        assertFalse(visible.contains("###"));
        assertTrue(splitter.meta().contains("factsToRemember"));
    }

    @Test
    void withoutMarkerEverythingIsReply() {
        List<String> shown = new ArrayList<>();
        ReplySplitter splitter = new ReplySplitter(shown::add);

        splitter.feed("⏰ Time to call Maria!");
        splitter.finish();

        assertEquals("⏰ Time to call Maria!", String.join("", shown));
        assertEquals("", splitter.meta());
    }

    @Test
    void listOpsAreParsedAndInvalidOnesDropped() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        ListService.ListOp add = ReplyExtraction.parseListOp(json.readTree(
                "{\"op\":\"add\",\"list\":\"shopping\",\"items\":[\"milk\",\" \"]}"));
        assertEquals(ListService.Op.ADD, add.op());
        assertEquals(List.of("milk"), add.items());
        ListService.ListOp clear = ReplyExtraction.parseListOp(json.readTree("{\"op\":\"clear\",\"list\":\"shopping\"}"));
        assertEquals(ListService.Op.CLEAR, clear.op());
        assertEquals(null, ReplyExtraction.parseListOp(json.readTree("{\"op\":\"explode\",\"list\":\"x\",\"items\":[\"a\"]}")));
        assertEquals(null, ReplyExtraction.parseListOp(json.readTree("{\"op\":\"add\",\"list\":\"shopping\",\"items\":[]}")));
    }

    @Test
    void listItemMatchingIgnoresCaseAndGreekAccents() {
        assertEquals(ListService.key("γάλα"), ListService.key("ΓΑΛΑ"));
        assertEquals("γαλα", ListService.key(" Γάλα "));
    }

    @Test
    void factKeysAreNormalized() {
        assertEquals("squat_max", ReplyExtraction.normalizeKey(" Squat Max "));
        assertEquals("run_5k_best", ReplyExtraction.normalizeKey("run-5k best"));
        assertEquals(null, ReplyExtraction.normalizeKey("  !! "));
    }

    @Test
    void greekMessagesGetAnExplicitGreekDirective() {
        assertTrue(PromptBuilder.languageDirective("πάμε για καφέ αύριο;").contains("Greek"));
        assertFalse(PromptBuilder.languageDirective("shall we get coffee tomorrow?").contains("Greek"));
    }
}
