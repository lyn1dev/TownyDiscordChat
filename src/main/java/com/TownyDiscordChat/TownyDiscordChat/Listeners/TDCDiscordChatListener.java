package com.TownyDiscordChat.TownyDiscordChat.Listeners;

import com.TownyDiscordChat.TownyDiscordChat.Main;
import github.scarsz.discordsrv.dependencies.jda.api.events.message.MessageReceivedEvent;
import github.scarsz.discordsrv.dependencies.jda.api.events.interaction.SlashCommandEvent;
import github.scarsz.discordsrv.dependencies.jda.api.events.interaction.ButtonClickEvent;
import github.scarsz.discordsrv.dependencies.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.util.regex.Pattern;

/** Relays messages from a configured town channel only to online residents of that town. */
public final class TDCDiscordChatListener extends ListenerAdapter {
    private static final Pattern CUSTOM_DISCORD_EMOJI = Pattern.compile("<a?:[A-Za-z0-9_]+:[0-9]+>");
    private static final Pattern ORPHAN_MENTION = Pattern.compile("(?<!\\S)@(?![\\p{L}\\p{N}_])");
    private final Main plugin;

    public TDCDiscordChatListener(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.getMessage().getContentRaw().isBlank()) {
            return;
        }
        String discordId = event.getAuthor().getId();
        String channelId = event.getChannel().getId();
        String author = event.getMember() == null ? event.getAuthor().getName() : event.getMember().getEffectiveName();
        // Resolve role/user/channel IDs such as <@&123> to their readable Discord names.
        String message = event.getMessage().getContentDisplay();
        if (plugin.configuration().getBoolean("bridge.RemoveUnsupportedDiscordEmoji", true)) {
            author = cleanForMinecraft(author);
            message = cleanForMinecraft(message);
        }
        if (message.isBlank()) return;
        String cleanAuthor = author;
        String cleanMessage = message;
        plugin.getServer().getScheduler().runTask(plugin,
                () -> plugin.manager().relayDiscordMessage(discordId, channelId, cleanAuthor, cleanMessage));
    }

    /** Removes emoji code points and invisible Discord direction/variation markers unsupported by Minecraft's font. */
    private String cleanForMinecraft(String input) {
        String value = CUSTOM_DISCORD_EMOJI.matcher(input == null ? "" : input).replaceAll("");
        StringBuilder clean = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            boolean invisibleOrPrivate = type == Character.FORMAT || type == Character.PRIVATE_USE
                    || type == Character.SURROGATE || type == Character.UNASSIGNED;
            boolean emoji = (codePoint >= 0x1F000 && codePoint <= 0x1FFFF)
                    || (codePoint >= 0x2300 && codePoint <= 0x23FF)
                    || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                    || (codePoint >= 0x2B00 && codePoint <= 0x2BFF)
                    || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
                    || codePoint == 0x20E3
                    || (codePoint >= 0xE0020 && codePoint <= 0xE007F)
                    || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                    || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
            if (!invisibleOrPrivate && !emoji) clean.appendCodePoint(codePoint);
        });
        return ORPHAN_MENTION.matcher(clean.toString()).replaceAll("")
                .replaceAll("[\\p{Zs}\\t]{2,}", " ").trim();
    }

    @Override
    public void onButtonClick(@NotNull ButtonClickEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("tdc:taxes:") && !id.startsWith("tdc:residents:") && !id.startsWith("tdc:outposts:")) return;
        // The optional fifth segment identifies the navigation direction and
        // keeps both Discord component IDs unique even on a single-page list.
        String[] parts = id.split(":", 5);
        if (parts.length < 3) {
            event.reply("This button isn't valid any more.").setEphemeral(true).queue();
            return;
        }
        String action = parts[1];
        String townName = parts[2];
        int parsedPage = 0;
        if (parts.length >= 4) {
            try {
                parsedPage = Math.max(0, Integer.parseInt(parts[3]));
            } catch (NumberFormatException ignored) {
                parsedPage = 0;
            }
        }
        int page = parsedPage;
        String discordId = event.getUser().getId();
        String channelId = event.getChannel().getId();
        boolean townButtonAdministrator = isTownButtonAdministrator(event);

        if (event.getMessage().isEphemeral()) {
            event.deferEdit().queue(hook -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                var response = plugin.manager().townButtonResponse(discordId, channelId, townName, action, page, townButtonAdministrator);
                if (response.error() != null) {
                    hook.editOriginal(response.error()).setEmbeds(java.util.Collections.emptyList())
                            .setActionRows(java.util.Collections.emptyList()).queue();
                } else {
                    hook.editOriginalEmbeds(response.embed()).setActionRow(response.buttons()).queue();
                }
            }));
        } else {
            event.deferReply(true).queue(hook -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                var response = plugin.manager().townButtonResponse(discordId, channelId, townName, action, page, townButtonAdministrator);
                if (response.error() != null) {
                    hook.editOriginal(response.error()).queue();
                } else {
                    var update = hook.editOriginalEmbeds(response.embed());
                    if (!response.buttons().isEmpty()) update.setActionRow(response.buttons());
                    update.queue();
                }
            }));
        }
    }

    private boolean isTownButtonAdministrator(ButtonClickEvent event) {
        if (event.getMember() == null) return false;
        if (plugin.configuration().getBoolean("discord.TownButtons.AllowDiscordAdministrators", true)
                && event.getMember().hasPermission(github.scarsz.discordsrv.dependencies.jda.api.Permission.ADMINISTRATOR)) {
            return true;
        }
        java.util.List<String> roleIds = plugin.configuration().getStringList("discord.TownButtons.AdminRoleIds");
        return event.getMember().getRoles().stream().anyMatch(role -> roleIds.contains(role.getId()));
    }

    @Override
    public void onSlashCommand(@NotNull SlashCommandEvent event) {
        if (!event.getName().equals("town")) return;
        String subcommand = event.getSubcommandName();
        if (subcommand == null) {
            event.reply("❌ Missing subcommand.").setEphemeral(true).queue();
            return;
        }
        String message = event.getOption("messaggio") == null ? null : event.getOption("messaggio").getAsString();
        boolean administrator = event.getMember() != null && event.getMember().hasPermission(github.scarsz.discordsrv.dependencies.jda.api.Permission.ADMINISTRATOR);
        if (subcommand.equals("map")) {
            String town = event.getOption("citta") == null ? null : event.getOption("citta").getAsString();
            event.deferReply(false).queue(hook -> plugin.getServer().getScheduler().runTask(plugin,
                    () -> plugin.manager().requestTownDynmapMap(event.getUser().getId(), town, administrator, result -> {
                        if (!result.successful()) {
                            hook.editOriginal(result.error()).queue();
                            return;
                        }
                        event.getChannel().sendFile(result.image().toFile(), "dynmap-" + result.town() + ".png")
                                .content("🗺️ **" + result.town() + "** · Dynmap map from above")
                                .queue(ignored -> {
                                    try {
                                        Files.deleteIfExists(result.image());
                                    } catch (Exception ignoredDelete) {
                                        // The temporary map can safely be cleaned on a later request.
                                    }
                                }, error -> {
                                    try {
                                        Files.deleteIfExists(result.image());
                                    } catch (Exception ignoredDelete) {
                                        // The temporary map can safely be cleaned on a later request.
                                    }
                                });
                        hook.editOriginal("✅ Dynmap map sent to the channel.").queue();
                    })));
            return;
        }
        event.deferReply(true).queue(hook -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            String response = plugin.manager().handleTownSlashCommand(event.getUser().getId(), subcommand, message, administrator);
            hook.editOriginal(response).queue();
        }));
    }
}
