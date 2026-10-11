package com.niuqu.chatbubble.packets;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One chunk of a recipient-scoped group directory (2.5.0 v2).
 *
 * <p>No loader fragments a custom payload: all three send one vanilla custom-payload
 * packet per message, so a directory is limited by the packet frame size. A directory
 * is bounded by the server's own configuration — up to {@code group_max_count} (500)
 * groups with up to {@code group_max_members} (1000) members each — which can exceed
 * that size, so a large directory travels as a sequence of chunks carrying slices
 * of the {@link GroupDirectoryPacket} wire bytes.
 *
 * <p>The client reassembles one transfer and then decodes the <em>single complete</em>
 * directory with the ordinary payload codec, so the UI still applies exactly one full
 * snapshot (a per-packet replace would let an early chunk win over the rest). The
 * ledger keeps at most one transfer, with a hard chunk-count/byte budget and a
 * timeout, so a hostile or truncated stream cannot grow the client's memory.
 */
public record GroupDirectoryChunkPacket(UUID transferId, int index, int count, byte[] data) {

    /** Bytes of directory wire data per chunk, well below the single-packet frame limit. */
    public static final int MAX_CHUNK_BYTES = 256 * 1024;
    /** Chunk ceiling: together with the chunk size this bounds one transfer at 16 MiB. */
    public static final int MAX_CHUNKS = 64;
    /** An unfinished transfer is dropped after this long, so a lost chunk cannot pin memory. */
    static final long TRANSFER_TIMEOUT_MS = 30_000;

    public static void encode(GroupDirectoryChunkPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.transferId);
        buf.writeVarInt(packet.index);
        buf.writeVarInt(packet.count);
        buf.writeByteArray(packet.data);
    }

    public static GroupDirectoryChunkPacket decode(FriendlyByteBuf buf) {
        return new GroupDirectoryChunkPacket(buf.readUUID(), buf.readVarInt(), buf.readVarInt(),
            buf.readByteArray(MAX_CHUNK_BYTES));
    }

    public GroupDirectoryChunkPacket {
        if (transferId == null) throw new NullPointerException("transferId");
        if (data == null || data.length > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("group directory chunk exceeds its size budget");
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(this)));
        ctx.get().setPacketHandled(true);
    }

    /** Client-side hook, invoked from the packet handler. */
    public static void handleClient(GroupDirectoryChunkPacket payload) {
        GroupDirectoryPacket complete = assemble(payload, System.currentTimeMillis());
        if (complete != null) {
            GroupDirectoryPacket.applyClientDirectory(complete.entries(), complete.invitations());
        }
    }

    /**
     * Feeds one chunk into the single transfer ledger and returns the decoded
     * directory once the last chunk arrived. Anything invalid, oversized or late
     * is refused (and logged), never partially applied.
     */
    static GroupDirectoryPacket assemble(GroupDirectoryChunkPacket payload, long nowMs) {
        synchronized (LOCK) {
            if (payload.count() < 1 || payload.count() > MAX_CHUNKS
                    || payload.index() < 0 || payload.index() >= payload.count()) {
                com.mojang.logging.LogUtils.getLogger().warn(
                    "[e33chat] Ignoring malformed group directory chunk {} of {}", payload.index(), payload.count());
                return null;
            }
            if (ledger == null || !ledger.transferId.equals(payload.transferId())) {
                // A chunk from the most recently superseded transfer is a late arrival:
                // drop it before it can restart that transfer. Only that single slot is
                // remembered — starting a new transfer overwrites it — which is enough
                // because at most one transfer can be abandoned at a time.
                if (payload.transferId().equals(abandonedId)) return null;
                abandonedId = ledger != null ? ledger.transferId : abandonedId;
                ledger = new Ledger(payload.transferId(), payload.count(), nowMs);
            } else if (nowMs - ledger.startedAtMs > TRANSFER_TIMEOUT_MS) {
                ledger = new Ledger(payload.transferId(), payload.count(), nowMs);
            } else if (ledger.count != payload.count()) {
                com.mojang.logging.LogUtils.getLogger().warn(
                    "[e33chat] Group directory transfer changed its chunk count; restarting");
                ledger = new Ledger(payload.transferId(), payload.count(), nowMs);
            }
            if (ledger.parts[payload.index()] != null) return null;   // duplicate chunk
            ledger.parts[payload.index()] = payload.data();
            ledger.bytes += payload.data().length;
            ledger.received++;
            if (ledger.received < ledger.count) return null;
            byte[] all = new byte[ledger.bytes];
            int offset = 0;
            for (byte[] part : ledger.parts) {
                System.arraycopy(part, 0, all, offset, part.length);
                offset += part.length;
            }
            ledger = null;
            return decode(all);
        }
    }

    /** Drops every partially received transfer (client disconnect or world change). */
    public static void resetTransfers() {
        synchronized (LOCK) {
            ledger = null;
            abandonedId = null;
        }
    }

    private static GroupDirectoryPacket decode(byte[] bytes) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            return GroupDirectoryPacket.decode(buf);
        } catch (RuntimeException malformed) {
            com.mojang.logging.LogUtils.getLogger().warn(
                "[e33chat] Failed to decode the reassembled group directory", malformed);
            return null;
        } finally {
            buf.release();
        }
    }

    private static final Object LOCK = new Object();
    private static Ledger ledger;
    private static UUID abandonedId;

    private static final class Ledger {
        final UUID transferId;
        final int count;
        final byte[][] parts;
        final long startedAtMs;
        int received;
        int bytes;

        Ledger(UUID transferId, int count, long startedAtMs) {
            this.transferId = transferId;
            this.count = count;
            this.parts = new byte[count][];
            this.startedAtMs = startedAtMs;
        }
    }

    /** Splits one encoded directory into chunks the client can reassemble. */
    public static List<GroupDirectoryChunkPacket> split(UUID transferId, byte[] encoded) {
        int count = (encoded.length + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES;
        if (count < 1 || count > MAX_CHUNKS) return null;
        List<GroupDirectoryChunkPacket> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int from = i * MAX_CHUNK_BYTES;
            int to = Math.min(encoded.length, from + MAX_CHUNK_BYTES);
            chunks.add(new GroupDirectoryChunkPacket(transferId, i, count,
                Arrays.copyOfRange(encoded, from, to)));
        }
        return chunks;
    }
}
