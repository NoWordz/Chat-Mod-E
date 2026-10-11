package com.niuqu.chatbubble.packets;

import com.niuqu.chatbubble.chat.GroupChannelState;
import com.niuqu.chatbubble.group.GroupDirectoryEntry;
import com.niuqu.chatbubble.group.GroupIdentity;
import com.niuqu.chatbubble.group.GroupInvitationSummary;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S2C recipient-scoped group directory (v2). Replaces the public v1
 * {@link GroupListPayload} for connections that ACKed the handshake: it lists
 * only the groups this recipient belongs to, keyed by the stable group UUID, so
 * a display name can be reused without another group's history riding along.
 */
public record GroupDirectoryPayload(List<GroupDirectoryEntry> entries,
                                    List<GroupInvitationSummary> invitations)
        implements CustomPacketPayload {

    private static final int MAX_NAME = 64;
    /** group_max_count is configurable up to 500, so a full directory is legal. */
    private static final int MAX_GROUPS = 500;
    /** group_max_members is configurable up to 1000. */
    private static final int MAX_MEMBERS_PER_GROUP = 1000;
    /** One live invitation per (group, invitee) bounds a recipient by the group count. */
    private static final int MAX_INVITATIONS = 500;
    /** Hostile count from the wire: preallocate at most this many slots. */
    private static final int MAX_PREALLOC = 1024;

    public static final Type<GroupDirectoryPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("e33chat", "group_directory_v2"));

    public static final StreamCodec<ByteBuf, GroupDirectoryPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public GroupDirectoryPayload decode(ByteBuf buf) {
            // Read every row in full before capping: a surplus row's bytes would
            // otherwise be left in the buffer and desync the invitation list.
            int entryCount = readCount(buf);
            List<GroupDirectoryEntry> entries = new ArrayList<>(Math.min(entryCount, MAX_PREALLOC));
            for (int i = 0; i < entryCount; i++) {
                GroupDirectoryEntry entry = readEntry(buf);
                if (entry != null && entries.size() < MAX_GROUPS) entries.add(entry);
            }
            int invitationCount = readCount(buf);
            List<GroupInvitationSummary> invitations = new ArrayList<>(Math.min(invitationCount, MAX_PREALLOC));
            for (int i = 0; i < invitationCount; i++) {
                GroupInvitationSummary invitation = readInvitation(buf);
                if (invitation != null && invitations.size() < MAX_INVITATIONS) invitations.add(invitation);
            }
            return new GroupDirectoryPayload(entries, invitations);
        }

        @Override
        public void encode(ByteBuf buf, GroupDirectoryPayload payload) {
            List<GroupDirectoryEntry> entries = payload.entries() != null ? payload.entries() : List.of();
            int entryCount = 0;
            for (GroupDirectoryEntry entry : entries) if (entry != null) entryCount++;
            writeVarInt(buf, entryCount);
            for (GroupDirectoryEntry entry : entries) {
                if (entry == null) continue;
                writeUuid(buf, entry.identity().id());
                writeString(buf, entry.identity().displayName(), MAX_NAME);
                writeUuid(buf, entry.ownerId());
                List<UUID> members = entry.memberIds();
                writeVarInt(buf, members.size());
                for (UUID member : members) writeUuid(buf, member);
                buf.writeLong(entry.visibleFromSequence());
                buf.writeBoolean(entry.legacyHistoryAllowed());
                buf.writeLong(entry.latestSequence());
                buf.writeLong(entry.retainedFromSequence());
            }
            List<GroupInvitationSummary> invitations =
                payload.invitations() != null ? payload.invitations() : List.of();
            int invitationCount = 0;
            for (GroupInvitationSummary invitation : invitations) if (invitation != null) invitationCount++;
            writeVarInt(buf, invitationCount);
            for (GroupInvitationSummary invitation : invitations) {
                if (invitation == null) continue;
                writeUuid(buf, invitation.id());
                writeUuid(buf, invitation.groupId());
                writeString(buf, invitation.groupName(), MAX_NAME);
                writeUuid(buf, invitation.inviterId());
                buf.writeLong(invitation.createdAtMs());
                buf.writeLong(invitation.expiresAtMs());
            }
        }
    };

    /** Raw ByteBuf lacks the varint/utf/uuid helpers — route through FriendlyByteBuf */
    private static void writeVarInt(ByteBuf buf, int value) {
        new FriendlyByteBuf(buf).writeVarInt(value);
    }

    private static int readVarInt(ByteBuf buf) {
        return new FriendlyByteBuf(buf).readVarInt();
    }

    private static int readCount(ByteBuf buf) {
        return Math.max(readVarInt(buf), 0);
    }

    private static void writeUuid(ByteBuf buf, UUID id) {
        new FriendlyByteBuf(buf).writeUUID(id);
    }

    private static UUID readUuid(ByteBuf buf) {
        return new FriendlyByteBuf(buf).readUUID();
    }

    private static void writeString(ByteBuf buf, String s, int cap) {
        new FriendlyByteBuf(buf).writeUtf(s, cap);
    }

    private static String readString(ByteBuf buf, int cap) {
        return new FriendlyByteBuf(buf).readUtf(cap);
    }

    /** One row; an inconsistent row (blank name, no owner among the members,
     *  out-of-range sequence) is dropped instead of killing the whole directory. */
    private static GroupDirectoryEntry readEntry(ByteBuf buf) {
        UUID id = readUuid(buf);
        String displayName = readString(buf, MAX_NAME);
        UUID ownerId = readUuid(buf);
        int memberCount = readCount(buf);
        List<UUID> members = new ArrayList<>(Math.min(memberCount, MAX_PREALLOC));
        for (int i = 0; i < memberCount; i++) members.add(readUuid(buf));
        long visibleFrom = buf.readLong();
        boolean legacyAllowed = buf.readBoolean();
        long latestSequence = buf.readLong();
        long retainedFrom = buf.readLong();
        if (members.size() > MAX_MEMBERS_PER_GROUP) {
            members = new ArrayList<>(members.subList(0, MAX_MEMBERS_PER_GROUP));
        }
        try {
            return new GroupDirectoryEntry(new GroupIdentity(id, displayName), ownerId, members,
                visibleFrom, legacyAllowed, latestSequence, retainedFrom);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private static GroupInvitationSummary readInvitation(ByteBuf buf) {
        UUID id = readUuid(buf);
        UUID groupId = readUuid(buf);
        String groupName = readString(buf, MAX_NAME);
        UUID inviterId = readUuid(buf);
        long createdAt = buf.readLong();
        long expiresAt = buf.readLong();
        try {
            return new GroupInvitationSummary(id, groupId, groupName, inviterId, createdAt, expiresAt);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    @Override
    public Type<GroupDirectoryPayload> type() { return TYPE; }

    public static void handleClient(GroupDirectoryPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // A complete snapshot is newer than any transfer still in flight, so a
            // partly received chunk set is dropped instead of racing this one.
            GroupDirectoryChunkPayload.resetTransfers();
            List<GroupChannelState.PageRequest> requests =
                GroupChannelState.applyV2Directory(payload.entries(), payload.invitations());
            // Bind pre-2.5 rows to a grant once per directory change: the store's
            // version guard skips a repeated apply, a history load re-attempts
            // after inserting late rows, and a name matching more than one grant
            // is left unbound.
            com.niuqu.chatbubble.store.ChatMessageStore.bindLegacyGroupRowsIfStale();
            for (GroupChannelState.PageRequest request : requests) {
                GroupHistoryRequestPayload.send(request.groupId(), request.after(), request.limit());
            }
        });
    }
}
