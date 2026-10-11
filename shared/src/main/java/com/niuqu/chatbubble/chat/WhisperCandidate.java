package com.niuqu.chatbubble.chat;

import java.util.UUID;

public record WhisperCandidate(UUID senderId, String name, String profileName) {}
