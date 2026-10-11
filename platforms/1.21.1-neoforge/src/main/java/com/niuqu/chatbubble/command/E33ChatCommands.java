package com.niuqu.chatbubble.command;

import com.niuqu.chatbubble.ChatBubbleMod;
import com.niuqu.chatbubble.config.ChatServerConfig;
import com.niuqu.chatbubble.network.NetworkHandler;
import com.niuqu.chatbubble.server.ChatServerListener;
import com.niuqu.chatbubble.chat.TemplateMatcher;
import com.niuqu.chatbubble.group.GroupRules;
import com.niuqu.chatbubble.packets.ServerConfigScreenPayload;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** Server-side management of message-format templates (/e33chat template ...). */
public class E33ChatCommands {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        var tpl = Commands.literal("template").requires(s -> s.hasPermission(2));
        tpl.then(Commands.literal("list").executes(ctx -> list(ctx.getSource())));

        tpl.then(Commands.literal("set")
            .then(Commands.literal("chat").then(Commands.argument("template", StringArgumentType.greedyString())
                .executes(ctx -> set(ctx.getSource(), ChatServerConfig.CHAT_TEMPLATES,
                    StringArgumentType.getString(ctx, "template")))))
            .then(Commands.literal("whisper").then(Commands.argument("template", StringArgumentType.greedyString())
                .executes(ctx -> set(ctx.getSource(), ChatServerConfig.WHISPER_TEMPLATES,
                    StringArgumentType.getString(ctx, "template"))))));

        tpl.then(Commands.literal("remove")
            .then(Commands.literal("chat").then(Commands.argument("index", IntegerArgumentType.integer(1))
                .executes(ctx -> remove(ctx.getSource(), ChatServerConfig.CHAT_TEMPLATES,
                    IntegerArgumentType.getInteger(ctx, "index")))))
            .then(Commands.literal("whisper").then(Commands.argument("index", IntegerArgumentType.integer(1))
                .executes(ctx -> remove(ctx.getSource(), ChatServerConfig.WHISPER_TEMPLATES,
                    IntegerArgumentType.getInteger(ctx, "index"))))));

        tpl.then(Commands.literal("clear")
            .then(Commands.literal("chat").executes(ctx -> clear(ctx.getSource(), ChatServerConfig.CHAT_TEMPLATES, true)))
            .then(Commands.literal("whisper").executes(ctx -> clear(ctx.getSource(), ChatServerConfig.WHISPER_TEMPLATES, false))));

        tpl.then(Commands.literal("test")
            .then(Commands.literal("chat").then(Commands.argument("index", IntegerArgumentType.integer(1))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                    .executes(ctx -> test(ctx.getSource(), ChatServerConfig.CHAT_TEMPLATES,
                        IntegerArgumentType.getInteger(ctx, "index"),
                        StringArgumentType.getString(ctx, "text"))))))
            .then(Commands.literal("whisper").then(Commands.argument("index", IntegerArgumentType.integer(1))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                    .executes(ctx -> test(ctx.getSource(), ChatServerConfig.WHISPER_TEMPLATES,
                        IntegerArgumentType.getInteger(ctx, "index"),
                        StringArgumentType.getString(ctx, "text")))))));

        event.getDispatcher().register(Commands.literal("e33chat")
            .then(Commands.literal("gui").requires(s -> s.hasPermission(2))
                .executes(ctx -> openServerGui(ctx.getSource())))
            .then(tpl)
            .then(groupCommands()));
    }

    /** Player-facing group management + the client's silent say entry (2.5.0).
     *
     * <p>Every name/text argument is {@code greedyString}: Brigadier's
     * {@code string()} (QUOTABLE_PHRASE) only accepts {@code [0-9A-Za-z_.-]}
     * unquoted, so a CJK group name parsed as an empty word plus trailing data and
     * the command was rejected with "Expected whitespace to end one argument".
     * Greedy reads the whole remainder instead; group references, player names and
     * group names never contain whitespace, so a two-part argument is split at its
     * first space.
     *
     * <p>These are the same lifecycle actions the client sends over
     * {@code e33chat:group_action_v2}; the command layer only resolves a group
     * reference/id and a player name to UUIDs, the server re-authorizes anyway.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> groupCommands() {
        var cm = Commands.literal("group");
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
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> pairCommand(
            String name, int action) {
        return literal(name).then(argument("rest")
            .executes(ctx -> {
                String[] parts = com.niuqu.chatbubble.server.GroupManager.splitSay(greedy(ctx, "rest"));
                return groupPairAction(ctx.getSource(), action, parts[0], parts[1]);
            }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return Commands.literal(name);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> argument(
            String name) {
        return Commands.argument(name, StringArgumentType.greedyString());
    }

    private static String greedy(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, String key) {
        return StringArgumentType.getString(ctx, key).trim();
    }

    private static net.minecraft.server.level.ServerPlayer playerOrNull(CommandSourceStack src) {
        return src.getPlayer();
    }

    /** The caller's own groups and live invitations — never the whole server roster. */
    private static int groupList(CommandSourceStack src) {
        var p = playerOrNull(src);
        if (p == null) {
            src.sendFailure(Component.translatable("e33chat.server.console_only"));
            return 0;
        }
        var groups = com.niuqu.chatbubble.server.GroupManager.groupsOf(p.getUUID());
        var invitations = com.niuqu.chatbubble.server.GroupManager.invitationsOf(p.getUUID());
        if (groups.isEmpty() && invitations.isEmpty()) {
            src.sendSuccess(() -> Component.translatable("e33chat.group.list_empty"), false);
            return 1;
        }
        src.sendSuccess(() -> Component.translatable("e33chat.group.my_groups_count", groups.size()), false);
        for (var record : groups) {
            String name = record.identity().displayName();
            int count = record.access().visibleFromSequence().size();
            src.sendSuccess(() -> Component.translatable("e33chat.group.list_entry_joined", name, count), false);
        }
        if (!invitations.isEmpty()) {
            src.sendSuccess(() -> Component.translatable("e33chat.group.my_invites_header", invitations.size()), false);
            for (var invitation : invitations) {
                String name = invitation.groupName();
                String inviter = com.niuqu.chatbubble.server.GroupManager.playerName(invitation.inviterId());
                src.sendSuccess(() -> Component.translatable("e33chat.group.invite_entry", name, inviter), false);
            }
        }
        return 1;
    }

    /** create / leave / disband: one group reference, no second argument. */
    private static int groupAction(CommandSourceStack src, int action, String reference,
                                   java.util.UUID subjectId, java.util.UUID invitationId, String value) {
        var p = playerOrNull(src);
        if (p == null) { src.sendFailure(Component.translatable("e33chat.server.console_only")); return 0; }
        java.util.UUID groupId = GroupRules.NO_SUBJECT;
        if (reference != null) {
            var resolved = com.niuqu.chatbubble.server.GroupManager.resolveGroupId(reference);
            if (resolved == null) {
                src.sendFailure(Component.translatable("e33chat.group.missing", reference));
                return 0;
            }
            groupId = resolved;
        }
        com.niuqu.chatbubble.server.GroupManager.applyAction(p, action, groupId, subjectId, invitationId, value);
        return 1;
    }

    /** invite / rename / kick / transfer / revoke: a group reference plus a player or a new name. */
    private static int groupPairAction(CommandSourceStack src, int action, String reference, String second) {
        var p = playerOrNull(src);
        if (p == null) { src.sendFailure(Component.translatable("e33chat.server.console_only")); return 0; }
        var groupId = com.niuqu.chatbubble.server.GroupManager.resolveGroupId(reference);
        if (groupId == null) {
            src.sendFailure(Component.translatable("e33chat.group.missing", reference));
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
                src.sendFailure(Component.translatable("e33chat.group.invite_gone"));
                return 0;
            }
            return groupAction(src, GroupRules.REVOKE, reference, GroupRules.NO_SUBJECT, invitationId, "");
        }
        return groupAction(src, action, reference, subject, GroupRules.NO_SUBJECT, "");
    }

    /** accept / reject: the caller's own live invitation in the named group. */
    private static int groupInviteAction(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, boolean accept) {
        var src = ctx.getSource();
        var p = playerOrNull(src);
        if (p == null) { src.sendFailure(Component.translatable("e33chat.server.console_only")); return 0; }
        String reference = greedy(ctx, "group");
        var groupId = com.niuqu.chatbubble.server.GroupManager.resolveGroupId(reference);
        if (groupId == null) {
            src.sendFailure(Component.translatable("e33chat.group.missing", reference));
            return 0;
        }
        var invitationId = com.niuqu.chatbubble.server.GroupManager.invitationIdFor(groupId, p.getUUID());
        if (invitationId == null) {
            src.sendFailure(Component.translatable("e33chat.group.invite_gone"));
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
    private static java.util.UUID resolvePlayer(CommandSourceStack src, String nameOrId) {
        if (nameOrId == null || nameOrId.isEmpty()) {
            src.sendFailure(Component.translatable("e33chat.group.no_target"));
            return null;
        }
        java.util.UUID resolved =
            com.niuqu.chatbubble.server.GroupManager.resolvePlayerId(src.getServer(), nameOrId);
        if (resolved == null) {
            src.sendFailure(Component.translatable("e33chat.group.player_unknown", nameOrId));
        }
        return resolved;
    }

    private static int groupSay(CommandSourceStack src, String reference, String text) {
        var p = playerOrNull(src);
        if (p == null) { src.sendFailure(Component.translatable("e33chat.server.console_only")); return 0; }
        var groupId = com.niuqu.chatbubble.server.GroupManager.resolveGroupId(reference);
        if (groupId == null) {
            src.sendFailure(Component.translatable("e33chat.group.missing", reference));
            return 0;
        }
        com.niuqu.chatbubble.server.GroupManager.say(p, groupId, text);
        return 1;
    }

    // Opens the server-config GUI on the executing player's client (S2C snapshot)
    private static int openServerGui(CommandSourceStack src) {
        var player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.translatable("e33chat.server.console_only"));
            return 0;
        }
        PacketDistributor.sendToPlayer(player,
            new ServerConfigScreenPayload(ChatServerConfig.USE_TPA.get(),
                ChatServerConfig.HISTORY_ENABLED.get(), ChatServerConfig.TEMPLATE_DEBUG.get(),
                ChatServerConfig.MEDIA_ENABLED.get(), ChatServerConfig.MEDIA_AUTO_CLEAN.get(),
                ChatServerConfig.EASY_BOT_COMPAT.get(), ChatServerConfig.GROUPS_ENABLED.get(),
                new ArrayList<>(ChatServerConfig.CHAT_TEMPLATES.get()),
                new ArrayList<>(ChatServerConfig.WHISPER_TEMPLATES.get())));
        return 1;
    }

    private static int list(CommandSourceStack src) {
        src.sendSuccess(() -> Component.translatable("e33chat.server.tpl_list_chat_header", ChatServerConfig.CHAT_TEMPLATES.get().size()), false);
        printTemplates(src, ChatServerConfig.CHAT_TEMPLATES.get());
        src.sendSuccess(() -> Component.translatable("e33chat.server.tpl_list_whisper_header", ChatServerConfig.WHISPER_TEMPLATES.get().size()), false);
        printTemplates(src, ChatServerConfig.WHISPER_TEMPLATES.get());
        return 1;
    }

    private static void printTemplates(CommandSourceStack src, List<? extends String> templates) {
        if (templates.isEmpty()) {
            src.sendSuccess(() -> Component.translatable("e33chat.server.tpl_list_empty"), false);
            return;
        }
        int i = 1;
        for (String t : templates) {
            int idx = i++;
            src.sendSuccess(() -> Component.literal("  " + idx + ". " + t), false);
        }
    }

    private static int set(CommandSourceStack src, ModConfigSpec.ConfigValue<List<? extends String>> spec, String raw) {
        TemplateMatcher.CompileResult result = TemplateMatcher.compile(raw);
        if (result.template() == null) {
            src.sendFailure(Component.translatable("e33chat.server.tpl_set_invalid", result.error()));
            return 0;
        }
        if (!result.template().unknownFields().isEmpty()) {
            src.sendSuccess(() -> Component.translatable("e33chat.server.tpl_set_unknown_fields", result.template().unknownFields()), false);
        }
        List<String> next = new ArrayList<>(spec.get());
        if (next.contains(raw)) {
            src.sendFailure(Component.translatable("e33chat.server.tpl_set_duplicate"));
            return 0;
        }
        next.add(raw);
        updateTemplates(src, spec, next);
        src.sendSuccess(() -> Component.translatable("e33chat.server.tpl_set_added", next.size(), raw), false);
        return 1;
    }

    private static int remove(CommandSourceStack src, ModConfigSpec.ConfigValue<List<? extends String>> spec, int index) {
        List<String> next = new ArrayList<>(spec.get());
        if (index < 1 || index > next.size()) {
            src.sendFailure(Component.translatable("e33chat.server.tpl_remove_bad_index", next.size()) );
            return 0;
        }
        String removed = next.remove(index - 1);
        updateTemplates(src, spec, next);
        src.sendSuccess(() -> Component.translatable("e33chat.server.tpl_remove_done", removed), false);
        return 1;
    }

    private static int clear(CommandSourceStack src, ModConfigSpec.ConfigValue<List<? extends String>> spec, boolean chat) {
        updateTemplates(src, spec, List.of());
        src.sendSuccess(() -> Component.translatable("e33chat.server.tpl_clear_done",
            Component.translatable(chat ? "e33chat.server.kind_chat" : "e33chat.server.kind_whisper")), false);
        return 1;
    }

    private static int test(CommandSourceStack src, ModConfigSpec.ConfigValue<List<? extends String>> spec, int index, String text) {
        List<? extends String> raws = spec.get();
        if (index < 1 || index > raws.size()) {
            src.sendFailure(Component.translatable("e33chat.server.tpl_test_bad_index", raws.size()));
            return 0;
        }
        TemplateMatcher.CompileResult result = TemplateMatcher.compile(raws.get(index - 1));
        if (result.template() == null) {
            src.sendFailure(Component.translatable("e33chat.server.tpl_test_unparseable", result.error()));
            return 0;
        }
        boolean whisper = result.template().whisper();
        var match = TemplateMatcher.match(text,
            whisper ? List.of() : List.of(result.template()),
            whisper ? List.of(result.template()) : List.of(),
            name -> isKnownOnServer(src, name));
        if (match.isEmpty()) {
            src.sendSuccess(() -> Component.translatable("e33chat.server.tpl_test_no_match"), false);
            return 1;
        }
        var r = match.orElseThrow();
        src.sendSuccess(() -> Component.translatable("e33chat.server.tpl_test_matched",
                Component.translatable(whisper ? "e33chat.server.kind_whisper" : "e33chat.server.kind_chat")), false);
        if (r.prefix() != null) src.sendSuccess(() -> Component.translatable("e33chat.server.tpl_test_field_prefix", r.prefix()), false);
        if (r.displayName() != null) {
            src.sendSuccess(() -> {
                var field = Component.translatable("e33chat.server.tpl_test_field_name",
                    whisper ? "sender/target" : "display_name", r.displayName());
                return r.verifiedName() != null
                    ? field.copy().append(Component.translatable("e33chat.server.tpl_test_verified"))
                    : field;
            }, false);
        }
        if (r.sender() != null && r.target() != null) {
            src.sendSuccess(() -> Component.translatable("e33chat.server.tpl_test_field_sender", r.sender(), r.target()), false);
        }
        src.sendSuccess(() -> Component.translatable("e33chat.server.tpl_test_field_content", r.content()), false);
        src.sendSuccess(() -> Component.translatable("e33chat.server.tpl_test_field_offset",
                r.nameStart(), r.nameEnd(), r.contentStart(), r.contentEnd()), false);
        return 1;
    }

    private static void updateTemplates(CommandSourceStack src, ModConfigSpec.ConfigValue<List<? extends String>> spec,
                                        List<String> next) {
        spec.set(next);
        spec.clearCache();
        ChatBubbleMod.saveServerConfig();
        ChatServerListener.broadcastServerConfig();
    }

    // Server-side stand-in for the client's name-resolution gate: the executing
    // player is the client's self, and all online players are candidate names
    private static boolean isKnownOnServer(CommandSourceStack src, String name) {
        if (name == null || name.isEmpty()) return false;
        var server = src.getServer();
        var self = src.getPlayer();
        if (self != null) {
            String selfName = self.getName().getString();
            if (!selfName.isEmpty() && (name.equals(selfName) || name.contains(selfName))) return true;
        }
        for (var p : server.getPlayerList().getPlayers()) {
            String n = p.getName().getString();
            if (!n.isEmpty() && (name.equals(n) || name.contains(n))) return true;
        }
        return false;
    }
}
