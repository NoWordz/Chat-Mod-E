package com.niuqu.chatbubble.chat;

import java.util.UUID;

public record WhisperMatch(UUID senderId, String displayName, String profileName, String content) {}
