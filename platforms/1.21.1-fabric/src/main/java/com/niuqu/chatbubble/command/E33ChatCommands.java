package com.niuqu.chatbubble.command;

import com.niuqu.chatbubble.ChatBubbleMod;
import com.niuqu.chatbubble.chat.TemplateMatcher;
import com.niuqu.chatbubble.config.ServerConfig;
import com.niuqu.chatbubble.config.ServerConfigManager;
import com.niuqu.chatbubble.group.GroupRules;
import com.niuqu.chatbubble.network.ServerConfigScreenPayload;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Server-side management of message-format templates (/e33chat template ...). */
public class E33ChatCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var tpl = net.minecraft.server.command.CommandManager.literal("template")
                .requires(s -> s.hasPermissionLevel(2));
            tpl.then(net.minecraft.server.command.CommandManager.literal("list")
                .executes(ctx -> list(ctx.getSource())));

            tpl.then(net.minecraft.server.command.CommandManager.literal("set")
                .then(net.minecraft.server.command.CommandManager.literal("chat")
                    .then(net.minecraft.server.command.CommandManager.argument("template", StringArgumentType.greedyString())
                        .executes(ctx -> set(ctx.getSource(), true,
                            StringArgumentType.getString(ctx, "template")))))
                .then(net.minecraft.server.command.CommandManager.literal("whisper")
                    .then(net.minecraft.server.command.CommandManager.argument("template", StringArgumentType.greedyString())
                        .executes(ctx -> set(ctx.getSource(), false,
                            StringArgumentType.getString(ctx, "template"))))));

            tpl.then(net.minecraft.server.command.CommandManager.literal("remove")
                .then(net.minecraft.server.command.CommandManager.literal("chat")
                    .then(net.minecraft.server.command.CommandManager.argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> remove(ctx.getSource(), true,
                            IntegerArgumentType.getInteger(ctx, "index")))))
                .then(net.minecraft.server.command.CommandManager.literal("whisper")
                    .then(net.minecraft.server.command.CommandManager.argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> remove(ctx.getSource(), false,
                            IntegerArgumentType.getInteger(ctx, "index"))))));

            tpl.then(net.minecraft.server.command.CommandManager.literal("clear")
                .then(net.minecraft.server.command.CommandManager.literal("chat")
                    .executes(ctx -> clear(ctx.getSource(), true)))
                .then(net.minecraft.server.command.CommandManager.literal("whisper")
                    .executes(ctx -> clear(ctx.getSource(), false))));

            tpl.then(net.minecraft.server.command.CommandManager.literal("test")
                .then(net.minecraft.server.command.CommandManager.literal("chat")
                    .then(net.minecraft.server.command.CommandManager.argument("index", IntegerArgumentType.integer(1))
                        .then(net.minecraft.server.command.CommandManager.argument("text", StringArgumentType.greedyString())
                            .executes(ctx -> test(ctx.getSource(), true,
                                IntegerArgumentType.getInteger(ctx, "index"),
                                StringArgumentType.getString(ctx, "text"))))))
                .then(net.minecraft.server.command.CommandManager.literal("whisper")
                    .then(net.minecraft.server.command.CommandManager.argument("index", IntegerArgumentType.integer(1))
                        .then(net.minecraft.server.command.CommandManager.argument("text", StringArgumentType.greedyString())
                            .executes(ctx -> test(ctx.getSource(), false,
                                IntegerArgumentType.getInteger(ctx, "index"),
                                StringArgumentType.getString(ctx, "text")))))));

            dispatcher.register(net.minecraft.server.command.CommandManager.literal("e33chat")
                .then(net.minecraft.server.command.CommandManager.literal("gui")
                    .requires(s -> s.hasPermissionLevel(2))
                    .executes(ctx -> openServerGui(ctx.getSource())))
                .then(tpl)
                .then(groupCommands()));
        });
    }

    /** Player-facing group management + the client's silent say entry (2.5.0).
     *
     * <p>Every name/text argument is {@code greedyString}: Brigadier's
     * {@code string()} (QUOTABLE_PHRASE) only accepts {@code [0-9A-Za-z_.-]}
     * unquoted, so a CJK group name like 妈妈 parsed as an empty word plus
     * trailing data and the command was rejected with "Expected whitespace to
     * end one argument". Greedy reads the whole remainder instead; group
     * references, player names and group names never contain whitespace, so a
     * two-part argument is split at its first space.
     *
     * <p>These are the same lifecycle actions the client sends over
     * {@code e33chat:group_action_v2}; the command layer only resolves a group
     * reference/id and a player name to UUIDs, the server re-authorizes anyway.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> groupCommands() {
        var cm = net.minecraft.server.command.CommandManager.literal("group");
        cm.then(literal("list").executes(ctx -> groupList(ctx.getSource())));
        cm.then(literal("create")
            .then(argument("name")
                .executes(ctx -> groupAction(ctx.getSource(), GroupRules.CREATE, null,
                    GroupRules.NO_SUBJECT, GroupRules.NO_SUBJECT, greedy(ctx, "name")))));
        cm.then(pairCommand("invite", GroupRules.INVITE));
        cm.then(literal("accept")
            .then(argument("group")
                .executes(ctx -> groupInviteAction(ctx, true))));
        cm.then(literal("reject")
            .then(argument("group")
                .executes(ctx -> groupInviteAction(ctx, false))));
        cm.then(pairCommand("revoke", -1));
        cm.then(literal("leave")
            .then(argument("group")
                .executes(ctx -> groupAction(ctx.getSource(), GroupRules.LEAVE, greedy(ctx, "group"),
                    GroupRules.NO_SUBJECT, GroupRules.NO_SUBJECT, ""))));
        cm.then(pairCommand("rename", GroupRules.RENAME));
        cm.then(pairCommand("kick", GroupRules.KICK));
        cm.then(pairCommand("transfer", GroupRules.TRANSFER_OWNER));
        cm.then(literal("disband")
            .then(argument("group")
                .executes(ctx -> groupAction(ctx.getSource(), GroupRules.DISBAND, greedy(ctx, "group"),
                    GroupRules.NO_SUBJECT, GroupRules.NO_SUBJECT, ""))));
        cm.then(literal("msg")
            .then(argument("rest")
                .executes(ctx -> {
                    String[] parts = com.niuqu.chatbubble.server.GroupManager.splitSay(
                        StringArgumentType.getString(ctx, "rest"));
                    return groupSay(ctx.getSource(), parts[0], parts[1]);
                })));
        return cm;
    }

    /** {@code <group> <second>} as one greedy argument, split at the first space. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> pairCommand(
            String name, int action) {
        return literal(name).then(argument("rest")
            .executes(ctx -> {
                String[] parts = com.niuqu.chatbubble.server.GroupManager.splitSay(greedy(ctx, "rest"));
                return groupPairAction(ctx.getSource(), action, parts[0], parts[1]);
            }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> literal(String name) {
        return net.minecraft.server.command.CommandManager.literal(name);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, String> argument(
            String name) {
        return net.minecraft.server.command.CommandManager.argument(name, StringArgumentType.greedyString());
    }

    private static String greedy(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx, String key) {
        return StringArgumentType.getString(ctx, key).trim();
    }

    private static net.minecraft.server.network.ServerPlayerEntity playerOrNull(ServerCommandSource src) {
        return src.getPlayer();
    }

    /** The caller's own groups and live invitations — never the whole server roster. */
    private static int groupList(ServerCommandSource src) {
        var p = playerOrNull(src);
        if (p == null) {
            src.sendError(Text.translatable("e33chat.server.console_only"));
            return 0;
        }
        var groups = com.niuqu.chatbubble.server.GroupManager.groupsOf(p.getUuid());
        var invitations = com.niuqu.chatbubble.server.GroupManager.invitationsOf(p.getUuid());
        if (groups.isEmpty() && invitations.isEmpty()) {
            src.sendFeedback(() -> Text.translatable("e33chat.group.list_empty"), false);
            return 1;
        }
        src.sendFeedback(() -> Text.translatable("e33chat.group.my_groups_count", groups.size()), false);
        for (var record : groups) {
            String name = record.identity().displayName();
            int count = record.access().visibleFromSequence().size();
            src.sendFeedback(() -> Text.translatable("e33chat.group.list_entry_joined", name, count), false);
        }
        if (!invitations.isEmpty()) {
            src.sendFeedback(() -> Text.translatable("e33chat.group.my_invites_header", invitations.size()), false);
            for (var invitation : invitations) {
                String name = invitation.groupName();
                String inviter = com.niuqu.chatbubble.server.GroupManager.playerName(invitation.inviterId());
                src.sendFeedback(() -> Text.translatable("e33chat.group.invite_entry", name, inviter), false);
            }
        }
        return 1;
    }

    /** create / leave / disband: one group reference, no second argument. */
    private static int groupAction(ServerCommandSource src, int action, String reference,
                                   java.util.UUID subjectId, java.util.UUID invitationId, String value) {
        var p = playerOrNull(src);
        if (p == null) { src.sendError(Text.translatable("e33chat.server.console_only")); return 0; }
        java.util.UUID groupId = GroupRules.NO_SUBJECT;
        if (reference != null) {
            var resolved = com.niuqu.chatbubble.server.GroupManager.resolveGroupId(reference);
            if (resolved == null) {
                src.sendError(Text.translatable("e33chat.group.missing", reference));
                return 0;
            }
            groupId = resolved;
        }
        com.niuqu.chatbubble.server.GroupManager.applyAction(p, action, groupId, subjectId, invitationId, value);
        return 1;
    }

    /** invite / rename / kick / transfer / revoke: a group reference plus a player or a new name. */
    private static int groupPairAction(ServerCommandSource src, int action, String reference, String second) {
        var p = playerOrNull(src);
        if (p == null) { src.sendError(Text.translatable("e33chat.server.console_only")); return 0; }
        var groupId = com.niuqu.chatbubble.server.GroupManager.resolveGroupId(reference);
        if (groupId == null) {
            src.sendError(Text.translatable("e33chat.group.missing", reference));
            return 0;
        }
        if (action == GroupRules.RENAME) {
            return groupAction(src, GroupRules.RENAME, reference, GroupRules.NO_SUBJECT,
                GroupRules.NO_SUBJECT, second);
        }
        var subject = resolvePlayer(src, second);
        if (subject == null) return 0;
        if (action == -1) {   // revoke: find the invitee's live invitation in that group
            var invitationId = com.niuqu.chatbubble.server.GroupManager.invitationIdFor(groupId, subject);
            if (invitationId == null) {
                src.sendError(Text.translatable("e33chat.group.invite_gone"));
                return 0;
            }
            return groupAction(src, GroupRules.REVOKE, reference, GroupRules.NO_SUBJECT, invitationId, "");
        }
        return groupAction(src, action, reference, subject, GroupRules.NO_SUBJECT, "");
    }

    /** accept / reject: the caller's own live invitation in the named group. */
    private static int groupInviteAction(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx,
                                         boolean accept) {
        var src = ctx.getSource();
        var p = playerOrNull(src);
        if (p == null) { src.sendError(Text.translatable("e33chat.server.console_only")); return 0; }
        String reference = greedy(ctx, "group");
        var groupId = com.niuqu.chatbubble.server.GroupManager.resolveGroupId(reference);
        if (groupId == null) {
            src.sendError(Text.translatable("e33chat.group.missing", reference));
            return 0;
        }
        var invitationId = com.niuqu.chatbubble.server.GroupManager.invitationIdFor(groupId, p.getUuid());
        if (invitationId == null) {
            src.sendError(Text.translatable("e33chat.group.invite_gone"));
            return 0;
        }
        return groupAction(src, accept ? GroupRules.ACCEPT : GroupRules.REJECT, null,
            GroupRules.NO_SUBJECT, invitationId, "");
    }

    /**
     * Command-side target resolution: the local failure messages stay here, the
     * lookup itself is {@link com.niuqu.chatbubble.server.GroupManager#resolvePlayerId}
     * so commands and the GUI's wire invite share one implementation.
     */
    private static java.util.UUID resolvePlayer(ServerCommandSource src, String nameOrId) {
        if (nameOrId == null || nameOrId.isEmpty()) {
            src.sendError(Text.translatable("e33chat.group.no_target"));
            return null;
        }
        java.util.UUID resolved =
            com.niuqu.chatbubble.server.GroupManager.resolvePlayerId(src.getServer(), nameOrId);
        if (resolved == null) {
            src.sendError(Text.translatable("e33chat.group.player_unknown", nameOrId));
        }
        return resolved;
    }

    private static int groupSay(ServerCommandSource src, String reference, String text) {
        var p = playerOrNull(src);
        if (p == null) { src.sendError(Text.translatable("e33chat.server.console_only")); return 0; }
        var groupId = com.niuqu.chatbubble.server.GroupManager.resolveGroupId(reference);
        if (groupId == null) {
            src.sendError(Text.translatable("e33chat.group.missing", reference));
            return 0;
        }
        com.niuqu.chatbubble.server.GroupManager.say(p, groupId, text);
        return 1;
    }

    // Opens the server-config GUI on the executing player's client (S2C snapshot)
    private static int openServerGui(ServerCommandSource src) {
        var player = src.getPlayer();
        if (player == null) {
            src.sendError(Text.translatable("e33chat.server.console_only"));
            return 0;
        }
        ServerPlayNetworking.send(player,
            new ServerConfigScreenPayload(ChatBubbleMod.useTpa(), ChatBubbleMod.historyEnabled(),
                ChatBubbleMod.templateDebug(), ChatBubbleMod.mediaEnabled(), ChatBubbleMod.mediaAutoClean(),
                ChatBubbleMod.easyBotCompat(), ChatBubbleMod.groupsEnabled(),
                new ArrayList<>(ChatBubbleMod.chatTemplates()),
                new ArrayList<>(ChatBubbleMod.whisperTemplates())));
        return 1;
    }

    private static List<String> templates(boolean chat) {
        return chat ? ChatBubbleMod.chatTemplates() : ChatBubbleMod.whisperTemplates();
    }

    private static int list(ServerCommandSource src) {
        src.sendFeedback(() -> Text.translatable("e33chat.server.tpl_list_chat_header", templates(true).size()), false);
        printTemplates(src, templates(true));
        src.sendFeedback(() -> Text.translatable("e33chat.server.tpl_list_whisper_header", templates(false).size()), false);
        printTemplates(src, templates(false));
        return 1;
    }

    private static void printTemplates(ServerCommandSource src, List<String> templates) {
        if (templates.isEmpty()) {
            src.sendFeedback(() -> Text.translatable("e33chat.server.tpl_list_empty"), false);
            return;
        }
        int i = 1;
        for (String t : templates) {
            int idx = i++;
            src.sendFeedback(() -> Text.literal("  " + idx + ". " + t), false);
        }
    }

    private static int set(ServerCommandSource src, boolean chat, String raw) {
        TemplateMatcher.CompileResult result = TemplateMatcher.compile(raw);
        if (result.template() == null) {
            src.sendError(Text.translatable("e33chat.server.tpl_set_invalid", result.error()));
            return 0;
        }
        if (!result.template().unknownFields().isEmpty()) {
            src.sendFeedback(() -> Text.translatable("e33chat.server.tpl_set_unknown_fields", result.template().unknownFields()), false);
        }
        List<String> next = new ArrayList<>(templates(chat));
        if (next.contains(raw)) {
            src.sendError(Text.translatable("e33chat.server.tpl_set_duplicate"));
            return 0;
        }
        next.add(raw);
        updateTemplates(src, chat, next);
        src.sendFeedback(() -> Text.translatable("e33chat.server.tpl_set_added", next.size(), raw), false);
        return 1;
    }

    private static int remove(ServerCommandSource src, boolean chat, int index) {
        List<String> next = new ArrayList<>(templates(chat));
        if (index < 1 || index > next.size()) {
            src.sendError(Text.translatable("e33chat.server.tpl_remove_bad_index", next.size()));
            return 0;
        }
        String removed = next.remove(index - 1);
        updateTemplates(src, chat, next);
        src.sendFeedback(() -> Text.translatable("e33chat.server.tpl_remove_done", removed), false);
        return 1;
    }

    private static int clear(ServerCommandSource src, boolean chat) {
        updateTemplates(src, chat, List.of());
        src.sendFeedback(() -> Text.translatable("e33chat.server.tpl_clear_done",
            Text.translatable(chat ? "e33chat.server.kind_chat" : "e33chat.server.kind_whisper")), false);
        return 1;
    }

    private static int test(ServerCommandSource src, boolean chat, int index, String text) {
        List<String> raws = templates(chat);
        if (index < 1 || index > raws.size()) {
            src.sendError(Text.translatable("e33chat.server.tpl_test_bad_index", raws.size()));
            return 0;
        }
        TemplateMatcher.CompileResult result = TemplateMatcher.compile(raws.get(index - 1));
        if (result.template() == null) {
            src.sendError(Text.translatable("e33chat.server.tpl_test_unparseable", result.error()));
            return 0;
        }
        boolean whisper = result.template().whisper();
        var match = TemplateMatcher.match(text,
            whisper ? List.of() : List.of(result.template()),
            whisper ? List.of(result.template()) : List.of(),
            name -> isKnownOnServer(src, name));
        if (match.isEmpty()) {
            src.sendFeedback(() -> Text.translatable("e33chat.server.tpl_test_no_match"), false);
            return 1;
        }
        var r = match.orElseThrow();
        src.sendFeedback(() -> Text.translatable("e33chat.server.tpl_test_matched",
                Text.translatable(whisper ? "e33chat.server.kind_whisper" : "e33chat.server.kind_chat")), false);
        if (r.prefix() != null) src.sendFeedback(() -> Text.translatable("e33chat.server.tpl_test_field_prefix", r.prefix()), false);
        if (r.displayName() != null) {
            src.sendFeedback(() -> Text.translatable("e33chat.server.tpl_test_field_name",
                    whisper ? "sender/target" : "display_name", r.displayName())
                .copy().append(Text.translatable("e33chat.server.tpl_test_verified")), false);
        }
        if (r.sender() != null && r.target() != null) {
            src.sendFeedback(() -> Text.translatable("e33chat.server.tpl_test_field_sender", r.sender(), r.target()), false);
        }
        src.sendFeedback(() -> Text.translatable("e33chat.server.tpl_test_field_content", r.content()), false);
        src.sendFeedback(() -> Text.translatable("e33chat.server.tpl_test_field_offset",
                r.nameStart(), r.nameEnd(), r.contentStart(), r.contentEnd()), false);
        return 1;
    }

    private static void updateTemplates(ServerCommandSource src, boolean chat, List<String> next) {
        if (chat) ChatBubbleMod.setTemplates(new ArrayList<>(next), ChatBubbleMod.whisperTemplates(), ChatBubbleMod.templateDebug());
        else ChatBubbleMod.setTemplates(ChatBubbleMod.chatTemplates(), new ArrayList<>(next), ChatBubbleMod.templateDebug());
        // Persist to the per-world JSON and rebroadcast
        var server = src.getServer();
        var path = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
            .resolve("serverconfig").resolve("e33chat-server.json");
        ServerConfig cfg = new ServerConfig();
        // Every field must come from the live values: a partial snapshot gets
        // persisted, and primitives like media_enabled would poison the file
        // with false (nulls are omitted by Gson and fall back to defaults, a
        // primitive boolean is not).
        cfg.use_tpa = ChatBubbleMod.useTpa();
        cfg.history_enabled = ChatBubbleMod.historyEnabled();
        cfg.template_debug = ChatBubbleMod.templateDebug();
        cfg.chat_templates = new ArrayList<>(ChatBubbleMod.chatTemplates());
        cfg.whisper_templates = new ArrayList<>(ChatBubbleMod.whisperTemplates());
        cfg.media_enabled = ChatBubbleMod.mediaEnabled();
        cfg.media_auto_clean = ChatBubbleMod.mediaAutoClean();
        cfg.easy_bot_compat = ChatBubbleMod.easyBotCompat();
        cfg.groups_enabled = ChatBubbleMod.groupsEnabled();
        cfg.group_max_count = ChatBubbleMod.groupMaxCount();
        cfg.group_max_members = ChatBubbleMod.groupMaxMembers();
        cfg.group_create_op_only = ChatBubbleMod.groupCreateOpOnly();
        // The 2.5 group-history/invite knobs are not managed by this command; Gson
        // omits null fields, so saving a snapshot that never lifted them would reset
        // them to defaults on the next load (the GUI save path carries them over for
        // the same reason).
        cfg.group_history_retention_days = ChatBubbleMod.groupHistoryRetentionDays();
        cfg.group_history_max_messages = ChatBubbleMod.groupHistoryMaxMessages();
        cfg.group_history_max_mib = ChatBubbleMod.groupHistoryMaxMiB();
        cfg.group_invite_lifetime_days = ChatBubbleMod.groupInviteLifetimeDays();
        ServerConfigManager.save(path, cfg);
        ChatBubbleMod.broadcastServerConfig(server);
    }

    // Server-side stand-in for the client's name-resolution gate: the executing
    // player is the client's self, and all online players are candidate names
    private static boolean isKnownOnServer(ServerCommandSource src, String name) {
        if (name == null || name.isEmpty()) return false;
        var server = src.getServer();
        var self = src.getPlayer();
        if (self != null) {
            String selfName = self.getName().getString();
            if (!selfName.isEmpty() && (name.equals(selfName) || name.contains(selfName))) return true;
        }
        for (var p : server.getPlayerManager().getPlayerList()) {
            String n = p.getName().getString();
            if (!n.isEmpty() && (name.equals(n) || name.contains(n))) return true;
        }
        return false;
    }
}
