package com.niuqu.chatbubble.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Pure text checks shared by the official and Yarn whisper capture paths. */
public final class WhisperMessageMatcher {
    private WhisperMessageMatcher() {}

    private enum Direction { INCOMING, OUTGOING, UNKNOWN }

    public static boolean isWhisperFormat(String text) {
        return MessagePresentation.hasWhisperKeywordBeforeColon(text);
    }

    public static boolean mayUseGenericPlayerFallback(String text) {
        if (!isWhisperFormat(text)) return true;
        String clean = stripFormatting(text).stripLeading();
        if (!clean.startsWith("<")) return false;
        int close = clean.indexOf('>');
        if (close <= 1 || close >= 30) return false;
        return !isExplicitPrivateSuffix(clean.substring(close + 1).stripLeading());
    }

    private static boolean isExplicitPrivateSuffix(String suffix) {
        String lower = suffix.toLowerCase(Locale.ROOT);
        if (lower.startsWith("whisper to ") || lower.startsWith("whispers to ")
                || lower.startsWith("pm you") || lower.startsWith("msg you")
                || lower.startsWith("message to you") || lower.startsWith("tell you")
                || suffix.startsWith("-> 你") || lower.startsWith("-> you")) return true;
        if (suffix.startsWith("悄悄") && suffix.contains("对你说")) return true;
        if (suffix.startsWith("私信你") || suffix.startsWith("密语你")
                || suffix.startsWith("私聊你") || suffix.startsWith("密聊你")
                || suffix.startsWith("密谈你")) return true;
        String firstToken = suffix.split("\\s", 2)[0];
        String label = firstToken.toLowerCase(Locale.ROOT);
        // English keywords are ordinary verbs; without a "you" phrase they mean nothing,
        // so "<Steve> tell me about it" stays eligible for the generic player parse.
        if (label.equals("pm") || label.equals("msg") || label.equals("tell") || label.equals("message")) {
            return false;
        }
        return isPrivateLabel(label) && !label.equals("whisper") && !label.equals("whispers");
    }

    public static WhisperMatch findIncoming(
            String text, List<WhisperCandidate> candidates, String ownName) {
        if (!isWhisperFormat(text)) return null;
        String cleanText = stripFormatting(text);
        Map<UUID, WhisperMatch> matches = new LinkedHashMap<>();
        for (WhisperCandidate candidate : candidates) {
            if (candidate.senderId() == null) continue;
            String normalizedName = stripFormatting(candidate.name());
            if (!isIncomingCandidate(cleanText, normalizedName, ownName)) continue;
            String content = MessagePresentation.extractWhisperContent(cleanText, normalizedName);
            matches.putIfAbsent(candidate.senderId(), new WhisperMatch(candidate.senderId(),
                candidate.name(), candidate.profileName(), content));
        }
        return matches.size() == 1 ? matches.values().iterator().next() : null;
    }

    public static boolean isConfirmedOutgoingEcho(
            String text, String ownName, String pendingTarget) {
        return classifyDirection(stripFormatting(text), ownName, pendingTarget) == Direction.OUTGOING;
    }

    private static Direction classifyDirection(String text, String ownName, String pendingTarget) {
        String target = stripFormatting(pendingTarget);
        if (target.isEmpty()) return Direction.UNKNOWN;
        String header = header(text);
        int targetIndex = findHeaderName(header, target);
        if (targetIndex < 0) targetIndex = findExplicitOutgoingTarget(header, target, ownName);
        if (targetIndex < 0) return Direction.UNKNOWN;

        String afterTarget = header.substring(targetIndex + target.length()).toLowerCase(Locale.ROOT);
        if (namesUsAsRecipient(afterTarget, ownName)) return Direction.INCOMING;

        int selfEnd = selfSubjectEnd(header, ownName);
        if (selfEnd >= 0 && selfEnd < targetIndex
                && hasWhisperVerb(header.substring(selfEnd, targetIndex))) {
            return Direction.OUTGOING;
        }
        return Direction.UNKNOWN;
    }

    private static int findExplicitOutgoingTarget(String header, String target, String ownName) {
        int selfEnd = selfSubjectEnd(header, ownName);
        if (selfEnd < 0) return -1;
        int from = selfEnd;
        while (from < header.length()) {
            int index = header.indexOf(target, from);
            if (index < 0 || index >= 30) return -1;
            int end = index + target.length();
            boolean leftBoundary = index == 0 || !Names.isNameChar(header.charAt(index - 1));
            boolean rightBoundary = end >= header.length()
                || !Names.isNameChar(header.charAt(end))
                || startsChineseWhisperMarker(header, end)
                || header.startsWith("说", end);
            boolean directChineseRecipient = index > 0 && header.charAt(index - 1) == '对'
                && header.startsWith("说", end)
                && hasWhisperVerb(header.substring(selfEnd, index));
            if ((leftBoundary && rightBoundary) || directChineseRecipient) return index;
            from = index + 1;
        }
        return -1;
    }

    private static boolean namesUsAsRecipient(String afterTarget, String ownName) {
        if (containsRecipientName(afterTarget, "to ", "you")
                || containsRecipientName(afterTarget, "-> ", "you")) return true;
        // Plugin formats name the reader right after the keyword ("Steve PM you: hi");
        // the keyword alone is never enough, the phrase has to carry "you".
        for (String phrase : SECOND_PERSON_PHRASES) {
            if (mentionsWholePhrase(afterTarget, phrase)) return true;
        }
        for (String prefix : new String[]{"对", "私信", "密语", "私聊", "密聊", "密谈"}) {
            if (containsRecipientName(afterTarget, prefix, "你")) return true;
        }
        String own = stripFormatting(ownName).toLowerCase(Locale.ROOT);
        if (own.isEmpty()) return false;
        for (String prefix : new String[]{"to ", "-> ", "对", "私信", "密语", "私聊", "密聊", "密谈"}) {
            if (containsRecipientName(afterTarget, prefix, own)) return true;
        }
        return false;
    }

    /** Keyword phrases that already name the reader, kept in step with {@link #isExplicitPrivateSuffix}. */
    private static final String[] SECOND_PERSON_PHRASES = {
        "pm you", "msg you", "tell you", "message to you"};

    private static boolean mentionsWholePhrase(String text, String phrase) {
        int from = 0;
        while (from < text.length()) {
            int index = text.indexOf(phrase, from);
            if (index < 0) return false;
            int end = index + phrase.length();
            boolean leftBoundary = index == 0 || !Names.isNameChar(text.charAt(index - 1));
            boolean rightBoundary = end >= text.length() || !Names.isNameChar(text.charAt(end));
            if (leftBoundary && rightBoundary) return true;
            from = index + 1;
        }
        return false;
    }

    private static boolean isIncomingCandidate(String text, String name, String ownName) {
        int nameIndex = findHeaderName(text, name);
        if (nameIndex < 0) return false;
        String header = header(text);
        String beforeName = header.substring(0, nameIndex);
        String afterName = header.substring(nameIndex + name.length()).toLowerCase(Locale.ROOT);
        return namesUsAsRecipient(afterName, ownName)
            || isPrivateLabel(beforeName)
            || startsChinesePrivateLabel(afterName);
    }

    /** Mirror of the label-before-name form: Chinese plugins write "Steve 密谈: hi". */
    private static boolean startsChinesePrivateLabel(String afterName) {
        String trimmed = afterName.stripLeading();
        for (String label : new String[]{"私信", "密语", "私聊", "密聊", "密谈"}) {
            if (trimmed.startsWith(label)) return true;
        }
        return false;
    }

    private static boolean containsRecipientName(String text, String prefix, String name) {
        String needle = name.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from < text.length()) {
            int marker = text.indexOf(prefix, from);
            if (marker < 0) return false;
            int nameStart = marker + prefix.length();
            if (text.startsWith(needle, nameStart)) {
                int end = nameStart + needle.length();
                if (end == text.length() || !Names.isNameChar(text.charAt(end))
                        || text.startsWith("说", end)) return true;
            }
            from = marker + 1;
        }
        return false;
    }

    private static boolean isPrivateLabel(String prefix) {
        String label = prefix.trim();
        if (label.length() > 2) {
            char first = label.charAt(0);
            char last = label.charAt(label.length() - 1);
            if ((first == '[' && last == ']') || (first == '【' && last == '】')
                    || (first == '(' && last == ')')) {
                label = label.substring(1, label.length() - 1).trim();
            }
        }
        return switch (label.toLowerCase(Locale.ROOT)) {
            case "私聊", "私信", "密语", "密聊", "密谈", "whisper", "whispers", "pm", "msg", "message", "tell" -> true;
            default -> false;
        };
    }

    private static int selfSubjectEnd(String header, String ownName) {
        String trimmed = header.stripLeading();
        int offset = header.length() - trimmed.length();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("you") && (lower.length() == 3 || !Names.isNameChar(lower.charAt(3)))) {
            return offset + 3;
        }
        if (trimmed.startsWith("你")) return offset + 1;

        String own = stripFormatting(ownName);
        int ownIndex = findHeaderName(header, own);
        if (ownIndex >= 0 && isDecoration(header.substring(0, ownIndex))) {
            return ownIndex + own.length();
        }
        return -1;
    }

    private static boolean isDecoration(String prefix) {
        boolean insideTag = false;
        for (int i = 0; i < prefix.length(); i++) {
            char ch = prefix.charAt(i);
            if (ch == '[' || ch == '(' || ch == '<') {
                if (insideTag) return false;
                insideTag = true;
            } else if (ch == ']' || ch == ')' || ch == '>') {
                if (!insideTag) return false;
                insideTag = false;
            } else if (!insideTag && !Character.isWhitespace(ch)) {
                return false;
            }
        }
        return !insideTag;
    }

    private static boolean hasWhisperVerb(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("whisper") || lower.contains("pm") || lower.contains("msg")
            || lower.contains("tell") || lower.contains("悄悄") || lower.contains("私聊")
            || lower.contains("密语") || lower.contains("密聊") || lower.contains("私信")
            || lower.contains("密谈");
    }

    private static int findHeaderName(String text, String name) {
        if (text == null || name == null || name.isEmpty()) return -1;
        String cleanText = stripFormatting(text);
        String cleanName = stripFormatting(name);
        if (cleanName.isEmpty()) return -1;
        int limit = Math.min(headerEnd(cleanText), 30);
        int from = 0;
        while (from < limit) {
            int index = cleanText.indexOf(cleanName, from);
            if (index < 0 || index >= limit) return -1;
            int end = index + cleanName.length();
            boolean leftBoundary = index == 0 || !Names.isNameChar(cleanText.charAt(index - 1));
            boolean rightBoundary = end >= cleanText.length()
                || !Names.isNameChar(cleanText.charAt(end))
                || startsChineseWhisperMarker(cleanText, end);
            if (leftBoundary && rightBoundary) return index;
            from = index + 1;
        }
        return -1;
    }

    private static boolean startsChineseWhisperMarker(String text, int index) {
        for (String marker : new String[]{"悄悄", "对你说", "私聊", "密语", "密聊", "私信", "密谈"}) {
            if (text.startsWith(marker, index)) return true;
        }
        return false;
    }

    private static int headerEnd(String text) {
        int colon = text.indexOf(':');
        int fullWidthColon = text.indexOf('：');
        if (colon < 0) return fullWidthColon < 0 ? text.length() : fullWidthColon;
        if (fullWidthColon < 0) return colon;
        return Math.min(colon, fullWidthColon);
    }

    private static String header(String text) {
        return text.substring(0, headerEnd(text));
    }

    private static String stripFormatting(String text) {
        return text == null ? "" : text.replaceAll("§.", "");
    }
}
