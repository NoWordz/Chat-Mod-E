package com.niuqu.chatbubble.network;

import com.niuqu.chatbubble.group.GroupDirectoryEntry;
import com.niuqu.chatbubble.group.GroupIdentity;
import com.niuqu.chatbubble.group.GroupInvitationSummary;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S2C recipient-scoped group directory (2.5.0 v2). Every entry describes one group
 * the recipient currently belongs to; nothing about other players' groups is sent.
 *
 * <p>The DTOs are the shared group records ({@link GroupDirectoryEntry},
 * {@link GroupInvitationSummary}) so the server and the client agree on one shape.
 */
public record GroupDirectoryPayload(List<GroupDirectoryEntry> groups,
                                    List<GroupInvitationSummary> invitations)
        implements CustomPayload {

    // Decode-side caps: a hostile server must not balloon client memory, but the
    // caps must still cover every directory a valid server may hold. group_max_count
    // tops out at 500 and group_max_members at 1000, and one live invitation per
    // (group, invitee) bounds a recipient's invitations by the group count as well.
    private static final int MAX_GROUPS = 500;
    private static final int MAX_MEMBERS_PER_GROUP = 1000;
    private static final int MAX_INVITATIONS = 500;
    private static final int MAX_NAME = 64;

    public static final CustomPayload.Id<GroupDirectoryPayload> ID =
        new CustomPayload.Id<>(Identifier.of("e33chat", "group_directory_v2"));

    public static final PacketCodec<PacketByteBuf, GroupDirectoryPayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            List<GroupDirectoryEntry> groups = value.groups() != null ? value.groups() : List.of();
            buf.writeVarInt(groups.size());
            for (GroupDirectoryEntry entry : groups) {
                buf.writeUuid(entry.identity().id());
                buf.writeString(entry.identity().displayName(), MAX_NAME);
                buf.writeUuid(entry.ownerId());
                buf.writeVarInt(entry.memberIds().size());
                for (UUID member : entry.memberIds()) buf.writeUuid(member);
                buf.writeLong(entry.visibleFromSequence());
                buf.writeBoolean(entry.legacyHistoryAllowed());
                buf.writeLong(entry.latestSequence());
                buf.writeLong(entry.retainedFromSequence());
            }
            List<GroupInvitationSummary> invitations = value.invitations() != null ? value.invitations() : List.of();
            buf.writeVarInt(invitations.size());
            for (GroupInvitationSummary invitation : invitations) {
                buf.writeUuid(invitation.id());
                buf.writeUuid(invitation.groupId());
                buf.writeString(invitation.groupName(), MAX_NAME);
                buf.writeUuid(invitation.inviterId());
                buf.writeLong(invitation.createdAtMs());
                buf.writeLong(invitation.expiresAtMs());
            }
        },
        buf -> {
            // Read every declared row, member and invitation off the wire before
            // applying the caps: a surplus row's bytes would otherwise be left in the
            // buffer, and the invitation list that follows would be parsed at the wrong
            // offset (Forge/Neo consume every row in full the same way). Rows and member
            // UUIDs that will not be kept are consumed without allocating for them.
            int groupCount = Math.max(buf.readVarInt(), 0);
            List<GroupDirectoryEntry> groups = new ArrayList<>(Math.min(groupCount, MAX_GROUPS));
            for (int i = 0; i < groupCount; i++) {
                UUID id = buf.readUuid();
                String displayName = buf.readString(MAX_NAME);
                UUID ownerId = buf.readUuid();
                int memberCount = Math.max(buf.readVarInt(), 0);
                boolean keep = groups.size() < MAX_GROUPS;
                List<UUID> members = keep ? new ArrayList<>(Math.min(memberCount, MAX_MEMBERS_PER_GROUP)) : null;
                for (int m = 0; m < memberCount; m++) {
                    UUID member = buf.readUuid();
                    if (members != null && members.size() < MAX_MEMBERS_PER_GROUP) members.add(member);
                }
                long visibleFrom = buf.readLong();
                boolean legacyAllowed = buf.readBoolean();
                long latest = buf.readLong();
                long retained = buf.readLong();
                if (!keep) continue;
                GroupDirectoryEntry entry = directoryEntry(id, displayName, ownerId, members,
                    visibleFrom, legacyAllowed, latest, retained);
                // A malformed entry is dropped instead of aborting the whole directory.
                if (entry != null) groups.add(entry);
            }
            int invitationCount = Math.max(buf.readVarInt(), 0);
            List<GroupInvitationSummary> invitations = new ArrayList<>(Math.min(invitationCount, MAX_INVITATIONS));
            for (int i = 0; i < invitationCount; i++) {
                UUID id = buf.readUuid();
                UUID groupId = buf.readUuid();
                String groupName = buf.readString(MAX_NAME);
                UUID inviterId = buf.readUuid();
                long createdAt = buf.readLong();
                long expiresAt = buf.readLong();
                if (invitations.size() >= MAX_INVITATIONS) continue;
                GroupInvitationSummary summary = invitationSummary(id, groupId, groupName, inviterId,
                    createdAt, expiresAt);
                if (summary != null) invitations.add(summary);
            }
            return new GroupDirectoryPayload(groups, invitations);
        }
    );

    private static GroupDirectoryEntry directoryEntry(UUID id, String displayName, UUID ownerId,
                                                      List<UUID> members, long visibleFrom,
                                                      boolean legacyAllowed, long latest, long retained) {
        try {
            return new GroupDirectoryEntry(new GroupIdentity(id, displayName), ownerId, members,
                visibleFrom, legacyAllowed, latest, retained);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static GroupInvitationSummary invitationSummary(UUID id, UUID groupId, String groupName,
                                                            UUID inviterId, long createdAt, long expiresAt) {
        try {
            return new GroupInvitationSummary(id, groupId, groupName, inviterId, createdAt, expiresAt);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** Client-side hook, invoked from ChatBubbleClientSetup's receiver. */
    public static void handleClient(GroupDirectoryPayload payload) {
        // A complete snapshot is newer than any transfer still in flight, so a
        // partly received chunk set is dropped instead of racing this one.
        GroupDirectoryChunkPayload.resetTransfers();
        java.util.List<com.niuqu.chatbubble.chat.GroupChannelState.PageRequest> requests =
            com.niuqu.chatbubble.chat.GroupChannelState.applyV2Directory(payload.groups(), payload.invitations());
        // Bind pre-2.5 rows to a grant once per directory change: the store's
        // version guard skips a repeated apply, a history load re-attempts after
        // inserting late rows, and a name matching more than one grant is left
        // unbound.
        com.niuqu.chatbubble.store.ChatMessageStore.bindLegacyGroupRowsIfStale();
        if (requests.isEmpty()) return;
        if (!net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                .canSend(GroupHistoryRequestPayload.ID)) return;
        for (com.niuqu.chatbubble.chat.GroupChannelState.PageRequest request : requests) {
            GroupHistoryRequestPayload.send(request.groupId(), request.after(), request.limit());
        }
    }
}
