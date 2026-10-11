package com.niuqu.chatbubble.chat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhisperMessageMatcherTest {
    @Test
    void longerNameWinsAndContentMayMentionTheShorterName() {
        var ann = new WhisperCandidate(new UUID(0, 1), "Ann", "Ann");
        var anna = new WhisperCandidate(new UUID(0, 2), "Anna", "Anna");
        WhisperMatch match = WhisperMessageMatcher.findIncoming(
            "Anna whispers to you: hello Ann", List.of(ann, anna), "Me");

        assertNotNull(match);
        assertEquals(anna.senderId(), match.senderId());
        assertEquals("hello Ann", match.content());
    }

    @Test
    void coloredTabAliasIsNormalizedBeforeExtractingTheBody() {
        var candidate = new WhisperCandidate(new UUID(0, 3), "§6[VIP]Anna§r", "Anna");
        WhisperMatch match = WhisperMessageMatcher.findIncoming(
            "§6[VIP]Anna§r 悄悄地对你说：hi", List.of(candidate), "Me");

        assertNotNull(match);
        assertEquals(candidate.senderId(), match.senderId());
        assertEquals("hi", match.content());
    }

    @Test
    void recipientNameMustNotMatchAnAnnabellePrefix() {
        var steve = new WhisperCandidate(new UUID(0, 4), "Steve", "Steve");
        assertNull(WhisperMessageMatcher.findIncoming(
            "Steve whispers to Annabelle: hi", List.of(steve), "Ann"));
    }

    @Test
    void supportedChineseIncomingMarkersDoNotNeedSpaces() {
        for (String line : List.of(
                "Bob私信你：hi", "Bob密语你：hi", "Bob私聊你：hi",
                "Bob悄悄地对你说：hi")) {
            WhisperMatch match = WhisperMessageMatcher.findIncoming(
                line, List.of(new WhisperCandidate(new UUID(0, 5), "Bob", "Bob")), "Me");
            assertNotNull(match, line);
            assertEquals("hi", match.content(), line);
        }
    }

    @Test
    void pendingEchoDoesNotChangeAnotherIncomingSendersIdentity() {
        var steve = WhisperMessageMatcher.findIncoming(
            "Steve whispers to you: hello",
            List.of(new WhisperCandidate(new UUID(0, 6), "Steve", "Steve")), "Me");
        var bob = WhisperMessageMatcher.findIncoming(
            "Bob悄悄地对你说：hi",
            List.of(new WhisperCandidate(new UUID(0, 7), "Bob", "Bob")), "Me");

        assertNotNull(steve);
        assertEquals(new UUID(0, 6), steve.senderId());
        assertNotNull(bob);
        assertEquals(new UUID(0, 7), bob.senderId());
    }

    @Test
    void announcementWithWhisperKeywordIsNotClaimedAsAPlayer() {
        assertNull(WhisperMessageMatcher.findIncoming(
            "Server private whisper announcement: maintenance",
            List.of(new WhisperCandidate(new UUID(0, 8), "Server", "Server")), "Me"));
    }

    @Test
    void twoDifferentUuidsWithTheSameDisplayAliasAreUnknown() {
        var first = new WhisperCandidate(new UUID(0, 9), "[VIP]Anna", "Anna");
        var second = new WhisperCandidate(new UUID(0, 10), "[VIP]Anna", "AnnaClone");
        assertNull(WhisperMessageMatcher.findIncoming(
            "[VIP]Anna悄悄地对你说：hi", List.of(first, second), "Me"));
    }

    @Test
    void aliasesForOneUuidDoNotCreateAmbiguity() {
        UUID id = new UUID(0, 11);
        var tabAlias = new WhisperCandidate(id, "[VIP]Anna", "Anna");
        var profileName = new WhisperCandidate(id, "Anna", "Anna");
        WhisperMatch match = WhisperMessageMatcher.findIncoming(
            "[VIP]Anna悄悄地对你说：hi", List.of(tabAlias, profileName), "Me");

        assertNotNull(match);
        assertEquals(id, match.senderId());
        assertEquals("hi", match.content());
    }

    @Test
    void onlyAnExplicitOwnOutgoingWhisperCanBeSuppressed() {
        assertTrue(WhisperMessageMatcher.isConfirmedOutgoingEcho(
            "You whisper to Anna: sent", "Me", "Anna"));
        assertTrue(WhisperMessageMatcher.isConfirmedOutgoingEcho(
            "Me whispers to Anna: sent", "Me", "Anna"));
        assertTrue(WhisperMessageMatcher.isConfirmedOutgoingEcho(
            "你悄悄地对Anna说：sent", "Me", "Anna"));
        assertFalse(WhisperMessageMatcher.isConfirmedOutgoingEcho(
            "Anna whispers to you: reply", "Me", "Anna"));
        assertFalse(WhisperMessageMatcher.isConfirmedOutgoingEcho(
            "Server private whisper announcement: maintenance", "Me", "Anna"));
    }

    @Test
    void uncolonizedEnglishPlayerBodyCanContainWhisperWord() {
        assertTrue(WhisperMessageMatcher.mayUseGenericPlayerFallback(
            "<Steve> I might whisper later"));
    }

    @Test
    void uncolonizedChinesePlayerBodyCanContainPrivateWord() {
        assertTrue(WhisperMessageMatcher.mayUseGenericPlayerFallback(
            "<Steve> 我可以私聊朋友"));
    }

    @Test
    void unresolvedPrivateHeaderDoesNotFallBackToGuessingAName() {
        assertFalse(WhisperMessageMatcher.mayUseGenericPlayerFallback(
            "Anna whispers to you: hi"));
        assertFalse(WhisperMessageMatcher.mayUseGenericPlayerFallback(
            "[私聊] duplicateAlias: hi"));
    }

    @Test
    void englishSecondPersonKeywordFormatsStillResolveTheSender() {
        // Format contract: WhisperFormatsTest.pluginKeywordBeforeColonIsWhisper declares
        // these lines as whisper formats, so they must not degrade to plain text.
        for (String line : List.of(
                "Steve PM you: hi", "Steve pm you: hi", "Steve msg you: hi",
                "Steve tell you: hi", "Steve message to you: hi")) {
            WhisperMatch match = WhisperMessageMatcher.findIncoming(
                line, List.of(new WhisperCandidate(new UUID(0, 12), "Steve", "Steve")), "Me");
            assertNotNull(match, line);
            assertEquals(new UUID(0, 12), match.senderId(), line);
            assertEquals("hi", match.content(), line);
        }
    }

    @Test
    void chineseKeywordDirectlyAfterTheSenderNameResolvesTheSender() {
        // Mirror of the label-before-name form; WhisperFormatsTest:50 pins "Steve 密谈: hi".
        for (String line : List.of(
                "Steve 密谈: hi", "Steve 私聊: hi", "Steve 密语: hi", "Steve 私信: hi")) {
            WhisperMatch match = WhisperMessageMatcher.findIncoming(
                line, List.of(new WhisperCandidate(new UUID(0, 13), "Steve", "Steve")), "Me");
            assertNotNull(match, line);
            assertEquals(new UUID(0, 13), match.senderId(), line);
            assertEquals("hi", match.content(), line);
        }
    }

    @Test
    void ordinaryEnglishVerbWordsDoNotGatePublicRelayBodies() {
        assertTrue(WhisperMessageMatcher.mayUseGenericPlayerFallback("<Steve> tell me about it"));
        assertTrue(WhisperMessageMatcher.mayUseGenericPlayerFallback("<Steve> pm me the address"));
        assertFalse(WhisperMessageMatcher.mayUseGenericPlayerFallback("<Steve> tell you: hi"));
        assertFalse(WhisperMessageMatcher.mayUseGenericPlayerFallback("<Steve> pm you: hi"));
    }

    @Test
    void bareLabelGateStillCatchesExplicitPrivateSuffixes() {
        // Keeps both branches of isExplicitPrivateSuffix observable: the English
        // whisper exemption and the Chinese label after a bracketed name.
        assertTrue(WhisperMessageMatcher.mayUseGenericPlayerFallback("<Steve> whisper me the answer"));
        assertFalse(WhisperMessageMatcher.mayUseGenericPlayerFallback("<Steve> 密聊 Anna: hi"));
    }

    @Test
    void decoratedChineseWhisperTargetStillCountsAsOurOwnEcho() {
        assertTrue(WhisperMessageMatcher.isConfirmedOutgoingEcho(
            "你悄悄地对[称号]Steve说：hi", "Me", "Steve"));
        assertTrue(WhisperMessageMatcher.isConfirmedOutgoingEcho(
            "You whisper to [VIP]Steve: hi", "Me", "Steve"));
        assertFalse(WhisperMessageMatcher.isConfirmedOutgoingEcho(
            "你悄悄地对[称号]Steve说：hi", "Me", "Anna"));
    }

    @Test
    void milestoneGateSamplesAssignEachRealFormatToTheRightOwner() {
        // The four M6 acceptance samples (plan 6.4), one per language/format.
        // Each pair pins the fixed direction AND the old bug's wrong answer, so a
        // regression to the pre-M6 rules turns this red instead of merely throwing.

        // 1) Another player's incoming whisper, where the sender name is a strict
        //    prefix of a longer online alias (Ann vs Anna): the old first-substring
        //    scan claimed Ann; the real sender is Anna.
        var ann = new WhisperCandidate(new UUID(0, 20), "Ann", "Ann");
        var anna = new WhisperCandidate(new UUID(0, 21), "Anna", "Anna");
        WhisperMatch incoming = WhisperMessageMatcher.findIncoming(
            "Anna whispers to you: hey, are you there?", List.of(ann, anna), "Me");
        assertNotNull(incoming);
        assertEquals(anna.senderId(), incoming.senderId());
        assertEquals("hey, are you there?", incoming.content());

        // 2) Our own outgoing English whisper echo, with the pending target. The
        //    mirror line (Anna whispering US) must NOT be read as our echo — the old
        //    "no other player found, so it must be us" rule suppressed it.
        assertTrue(WhisperMessageMatcher.isConfirmedOutgoingEcho(
            "You whisper to Anna: sent", "Me", "Anna"));
        assertFalse(WhisperMessageMatcher.isConfirmedOutgoingEcho(
            "Anna whispers to you: reply", "Me", "Anna"));

        // 3) Our own outgoing Chinese echo — the plan's exact phrasing — and the
        //    same mirror guard for the Chinese incoming line.
        assertTrue(WhisperMessageMatcher.isConfirmedOutgoingEcho(
            "你悄悄地对Anna说：sent", "Me", "Anna"));
        assertFalse(WhisperMessageMatcher.isConfirmedOutgoingEcho(
            "Anna悄悄地对你说：reply", "Me", "Anna"));

        // 4) Another player's ordinary public chat: no whisper structure at all,
        //    so it must stay unclaimed and remain eligible for the generic parse.
        assertNull(WhisperMessageMatcher.findIncoming(
            "Anna: hello everyone",
            List.of(new WhisperCandidate(new UUID(0, 22), "Anna", "Anna")), "Me"));
        assertTrue(WhisperMessageMatcher.mayUseGenericPlayerFallback("Anna: hello everyone"));
    }
}
