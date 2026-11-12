package com.bot.botback.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamedReplyTest {

    @Test
    void forwardsEachPieceAndKeepsTheWholeReply() {
        List<String> shown = new ArrayList<>();
        StreamedReply reply = new StreamedReply(shown::add);

        for (String chunk : List.of("Hey! How", " was your", " day?")) {
            reply.feed(chunk);
        }

        assertEquals(List.of("Hey! How", " was your", " day?"), shown);
        assertEquals("Hey! How was your day?", reply.text());
        assertTrue(reply.emittedAny());
    }

    @Test
    void leadingBlankLinesAreNotShown() {
        List<String> shown = new ArrayList<>();
        StreamedReply reply = new StreamedReply(shown::add);

        reply.feed("\n\n");
        assertFalse(reply.emittedAny());
        reply.feed("  ⏰ Time to call Maria!\n");

        assertEquals(List.of("⏰ Time to call Maria!\n"), shown);
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
