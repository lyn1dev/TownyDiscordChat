package com.TownyDiscordChat.TownyDiscordChat;

import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.Permission;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Category;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Guild;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Member;
import github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed;
import github.scarsz.discordsrv.dependencies.jda.api.entities.PermissionOverride;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Role;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.dependencies.jda.api.entities.VoiceChannel;
import github.scarsz.discordsrv.dependencies.jda.api.requests.restaction.ChannelAction;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.commands.OptionType;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.commands.build.CommandData;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.commands.build.SubcommandData;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.components.Button;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Keeps Towny membership, Discord resources and the two chat directions consistent. */
public final class TDCManager {
    private static final String TOWN_PREFIX = "town-";
    private static final String NATION_PREFIX = "nation-";

    private final Main plugin;
    private final Map<String, CompletableFuture<Role>> pendingRoles = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<TextChannel>> pendingChannels = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastTownFallVariant = new ConcurrentHashMap<>();

    public TDCManager(Main plugin) {
        this.plugin = plugin;
    }

    public boolean isLinked(UUID playerId) {
        return DiscordSRV.getPlugin().getAccountLinkManager().getDiscordId(playerId) != null;
    }

    public void synchroniseAllResources() {
        for (Town town : new ArrayList<>(TownyUniverse.getInstance().getTowns())) {
            ensureTownResources(town);
        }
        for (Nation nation : new ArrayList<>(TownyUniverse.getInstance().getNations())) {
            ensureNationResources(nation);
        }
        // Remove channels left behind by deleted/renamed Towny objects. This is
        // deliberately limited to channels carrying a TownyDiscordChat role
        // permission, so unrelated Discord channels are never touched.
        cleanupObsoleteManagedChannels();
    }

    /** Deletes managed town/nation channels whose names no longer exist in Towny. */
    public int cleanupObsoleteManagedChannels() {
        Guild guild = guild();
        if (guild == null) return 0;
        Set<String> towns = TownyUniverse.getInstance().getTowns().stream()
                .map(Town::getName).map(this::normalise).collect(java.util.stream.Collectors.toSet());
        Set<String> nations = TownyUniverse.getInstance().getNations().stream()
                .map(Nation::getName).map(this::normalise).collect(java.util.stream.Collectors.toSet());
        int[] removed = {0};
        for (TextChannel channel : guild.getTextChannels()) {
            if (isObsoleteManagedChannel(channel, towns, nations)) {
                removed[0]++;
                channel.delete().queue();
            }
        }
        for (VoiceChannel channel : guild.getVoiceChannels()) {
            if (isObsoleteManagedChannel(channel, towns, nations)) {
                removed[0]++;
                channel.delete().queue();
            }
        }
        if (removed[0] > 0) log("Removed " + removed[0] + " obsolete TownyDiscordChat channel(s).");
        return removed[0];
    }

    private boolean isObsoleteManagedChannel(github.scarsz.discordsrv.dependencies.jda.api.entities.GuildChannel channel,
                                              Set<String> towns, Set<String> nations) {
        Category parent = channel.getParent();
        String categoryId = parent == null ? null : parent.getId();
        boolean townCategory = categoryId != null && (categoryId.equals(townTextCategoryId()) || categoryId.equals(townVoiceCategoryId()));
        boolean nationCategory = categoryId != null && (categoryId.equals(nationTextCategoryId()) || categoryId.equals(nationVoiceCategoryId()));
        if (!townCategory && !nationCategory) return false;
        boolean managed = channel.getRolePermissionOverrides().stream().map(PermissionOverride::getRole)
                .filter(java.util.Objects::nonNull).anyMatch(this::isManagedRole);
        if (!managed) return false;
        String name = normalise(channel.getName());
        return townCategory ? !towns.contains(name) : !nations.contains(name);
    }

    public void synchroniseAllLinkedAccounts() {
        Map<String, UUID> linked = DiscordSRV.getPlugin().getAccountLinkManager().getLinkedAccounts();
        linked.forEach((discordId, playerId) -> synchronisePlayer(discordId, playerId));
    }

    public void synchronisePlayer(UUID playerId) {
        String discordId = DiscordSRV.getPlugin().getAccountLinkManager().getDiscordId(playerId);
        if (discordId == null) {
            return;
        }
        synchronisePlayer(discordId, playerId);
    }

    /** Removes every obsolete managed role and adds precisely the player's current town/nation roles. */
    public void synchronisePlayer(String discordId, UUID playerId) {
        Guild guild = guild();
        if (guild == null) {
            return;
        }
        Member member = guild.getMemberById(discordId);
        if (member == null) {
            return;
        }

        Set<String> expected = expectedRoleNames(playerId);
        for (Role role : member.getRoles()) {
            if (isManagedRole(role) && !expected.contains(normalise(role.getName()))) {
                guild.removeRoleFromMember(member, role).queue(
                        ignored -> log("Removed obsolete Discord role " + role.getName() + " from " + member.getEffectiveName()),
                        error -> warn("Could not remove obsolete role " + role.getName(), error));
            }
        }

        for (String roleName : expected) {
            Role existing = roleByName(guild, roleName);
            if (existing != null) {
                addRoleIfMissing(guild, member, existing);
                continue;
            }
            ensureRole(roleName, roleName.startsWith(TOWN_PREFIX)).thenAccept(role -> addRoleIfMissing(guild, member, role));
        }
    }

    public void ensureTownResources(Town town) {
        String townName = town.getName();
        removeLegacyStaffChannels(townName);
        ensureRole(TOWN_PREFIX + townName, true).thenAccept(role -> {
            if (areTownChannelsDisabled(townName)) return;
            if (plugin.configuration().getBoolean("town.CreateTextChannelForRole", true)) {
                ensurePublicTextChannel(townName, townTextCategoryId(), role);
            }
            if (plugin.configuration().getBoolean("town.CreateVoiceChannelForRole", true)) {
                ensureVoiceChannel(townName, townVoiceCategoryId(), role);
            }
        });
    }

    public void ensureNationResources(Nation nation) {
        String nationName = nation.getName();
        ensureRole(NATION_PREFIX + nationName, false).thenAccept(role -> {
            if (plugin.configuration().getBoolean("nation.CreateTextChannelForRole", true)) {
                ensurePublicTextChannel(nationName, nationTextCategoryId(), role);
            }
            if (plugin.configuration().getBoolean("nation.CreateVoiceChannelForRole", true)) {
                ensureVoiceChannel(nationName, nationVoiceCategoryId(), role);
            }
        });
    }

    public void refreshTownStaff(Town town) {
        // Compatibility hook for older Towny events: staff channels have been retired.
        removeLegacyStaffChannels(town.getName());
    }

    /** Sends a notification to the normal text channel of the town. */
    public void sendTownNotification(Town town, String message) {
        if (areTownChannelsDisabled(town.getName())) return;
        ensureRole(TOWN_PREFIX + town.getName(), true).thenCompose(role ->
                ensurePublicTextChannel(town.getName(), townTextCategoryId(), role)).thenAccept(channel ->
                channel.sendMessage(message).queue(
                        ignored -> { }, error -> warn("Could not send town notification for " + town.getName(), error)));
    }

    /** Sends a configurable Towny event message with native and PlaceholderAPI values. */
    public void sendConfiguredTownEvent(Town town, String format, Map<String, String> values,
                                        org.bukkit.OfflinePlayer context) {
        sendTownNotification(town, configText(format, values, context));
    }

    /** Publishes Towny's own translated town-creation announcement in the newly created channel. */
    public void sendTownCreatedMessage(Town town, String townyMessage) {
        if (!plugin.configuration().getBoolean("messages.TownCreated.Enabled", true)) return;
        Resident mayor = town.getMayor();
        String format = plugin.configuration().getString("messages.TownCreated.Format", "🏙️ **Fondazione:** %towny_message%");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("town", town.getName());
        values.put("mayor", mayor == null ? "Nessuno" : mayor.getName());
        values.put("towny_message", townyMessage);
        org.bukkit.OfflinePlayer context = mayor == null ? null : Bukkit.getOfflinePlayer(mayor.getUUID());
        sendTownNotification(town, configText(format, values, context));
    }

    /** Captures information which Towny may discard when a town falls during NewDay. */
    public TownFallSnapshot captureTownFallSnapshot(Town town) {
        List<Resident> realResidents = town.getResidents().stream().filter(resident -> !isNpcResident(resident)).toList();
        List<String> citizens = realResidents.stream().map(Resident::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        double residentWealth = 0D;
        for (Resident resident : realResidents) {
            try {
                residentWealth += resident.getAccount().getHoldingBalance();
            } catch (RuntimeException | LinkageError ignored) {
                // Economy may be unavailable for an offline resident; keep the remaining values.
            }
        }
        Resident mayor = town.getMayor();
        Nation nation = nationFor(town);
        return new TownFallSnapshot(town.getName(), mayor == null ? "Nessuno" : mayor.getName(),
                mayor == null ? null : mayor.getUUID(), nation == null ? "Nessuna" : nation.getName(),
                citizens, realResidents.size(), town.getAccount().getHoldingBalance(), estimatedTownValue(town),
                residentWealth, realResidents.isEmpty() ? 0D : residentWealth / realResidents.size(),
                town.getTownBlocks().size(), town.getTaxes(), townUpkeep(town), town.isRuined(), town.isBankrupt());
    }

    /** Sends an original, configurable fall/bankruptcy story to DiscordSRV's global chat channel. */
    public void sendGlobalTownFallEmbed(TownFallSnapshot before, TownFallSnapshot after, String cause) {
        String root = "messages.GlobalTownFallEmbed";
        if (!plugin.configuration().getBoolean(root + ".Enabled", true)) return;
        TextChannel channel = DiscordSRV.getPlugin().getMainTextChannel();
        if (channel == null) {
            warn("DiscordSRV global chat channel is unavailable; could not announce the fall of " + before.town(), null);
            return;
        }
        TownFallSnapshot current = after == null ? before : after;
        String amountFormat = plugin.configuration().getString(root + ".AmountFormat", "%.2f");
        List<String> variants = plugin.configuration().getStringList(root + ".Variants");
        String story = variants.isEmpty()
                ? "Dopo un'ultima notte difficile, **%town%** non è più la città di ieri."
                : variants.get(selectTownFallVariant(before.town(), variants.size()));
        String causeKey = cause == null ? "fallen" : cause.toLowerCase(Locale.ROOT);
        String status = plugin.configuration().getString(root + ".CauseLabels." + causeKey,
                switch (causeKey) {
                    case "bankrupt" -> "Bancarotta";
                    case "ruined" -> "Rovina";
                    default -> "Caduta";
                });
        List<String> highlighted = before.citizens().stream()
                .filter(name -> !name.equalsIgnoreCase(before.mayor())).limit(5).toList();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("town", before.town());
        values.put("mayor", before.mayor());
        values.put("nation", before.nation());
        values.put("cause", causeKey);
        values.put("status", status);
        values.put("story", story);
        values.put("residents", String.valueOf(before.residentCount()));
        values.put("citizens", highlighted.isEmpty() ? "nessun cittadino registrato" : String.join(", ", highlighted));
        values.put("citizen_one", highlighted.isEmpty() ? before.mayor() : highlighted.getFirst());
        values.put("citizen_two", highlighted.size() < 2 ? before.mayor() : highlighted.get(1));
        values.put("balance_before", formatMoney(before.balance(), amountFormat));
        values.put("balance", formatMoney(current.balance(), amountFormat));
        values.put("town_value", formatMoney(before.townValue(), amountFormat));
        values.put("resident_wealth", formatMoney(before.residentWealth(), amountFormat));
        values.put("average_wealth", formatMoney(before.averageWealth(), amountFormat));
        values.put("plots", String.valueOf(before.plots()));
        values.put("tax", formatMoney(before.tax(), amountFormat));
        values.put("upkeep", formatMoney(before.upkeep(), amountFormat));
        org.bukkit.OfflinePlayer context = before.mayorId() == null ? null : Bukkit.getOfflinePlayer(before.mayorId());
        values.put("story", configText(story, values, context));
        MessageEmbed embed = buildConfiguredEmbed(root, values, context, "🏚️ " + status + " di " + before.town());
        channel.sendMessageEmbeds(embed).queue(ignored -> { },
                error -> warn("Could not announce the fall of " + before.town(), error));
    }

    private int selectTownFallVariant(String townName, int size) {
        if (size <= 1) return 0;
        int previous = lastTownFallVariant.getOrDefault(normalise(townName), -1);
        if (previous < 0 || previous >= size) {
            int selected = ThreadLocalRandom.current().nextInt(size);
            lastTownFallVariant.put(normalise(townName), selected);
            return selected;
        }
        int selected = ThreadLocalRandom.current().nextInt(size - 1);
        if (selected >= previous) selected++;
        lastTownFallVariant.put(normalise(townName), selected);
        return selected;
    }

    private boolean isNpcResident(Resident resident) {
        try {
            if (resident.isNPC()) return true;
        } catch (RuntimeException | LinkageError ignored) {
            // Fall through to Bukkit/Citizens metadata detection.
        }
        Player online = Bukkit.getPlayer(resident.getUUID());
        return online != null && online.hasMetadata("NPC");
    }

    private double estimatedTownValue(Town town) {
        try {
            Class<?> moneyUtil = Class.forName("com.palmergames.bukkit.towny.utils.MoneyUtil");
            Object value = moneyUtil.getMethod("getEstimatedValueOfTown", Town.class).invoke(null, town);
            return value instanceof Number number ? number.doubleValue() : 0D;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return 0D;
        }
    }

    /** Sends a configurable embed for each bank operation to the private town staff channel. */
    public void sendTownBankEmbed(Town town, String type, double amount, String actor, String reason) {
        if (areTownChannelsDisabled(town.getName())) return;
        if (!plugin.configuration().getBoolean("messages.BankEmbed.Enabled", true)) {
            sendTownNotification(town, "💰 **Banca città:** " + type + " `" + amount + "` da " + actor
                    + " · saldo: `" + town.getAccount().getHoldingBalance() + "`");
            return;
        }
        String amountFormat = plugin.configuration().getString("messages.BankEmbed.AmountFormat", "%.2f");
        String formattedAmount;
        String formattedBalance;
        try {
            formattedAmount = String.format(Locale.ROOT, amountFormat, amount);
            formattedBalance = String.format(Locale.ROOT, amountFormat, town.getAccount().getHoldingBalance());
        } catch (RuntimeException ignored) {
            formattedAmount = String.format(Locale.ROOT, "%.2f", amount);
            formattedBalance = String.format(Locale.ROOT, "%.2f", town.getAccount().getHoldingBalance());
        }
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("town", town.getName());
        placeholders.put("type", safe(type, "Operazione"));
        placeholders.put("amount", formattedAmount);
        placeholders.put("balance", formattedBalance);
        placeholders.put("actor", safe(actor, "Sistema"));
        placeholders.put("player", safe(actor, "Sistema"));
        placeholders.put("reason", safe(reason, "Non specificato"));
        placeholders.put("residents", String.valueOf(town.getNumResidents()));
        placeholders.put("tax", String.valueOf(town.getTaxes()));

        MessageEmbed embed = buildBankEmbed(placeholders, Bukkit.getOfflinePlayer(actor));
        ensureRole(TOWN_PREFIX + town.getName(), true).thenCompose(role ->
                ensurePublicTextChannel(town.getName(), townTextCategoryId(), role)).thenAccept(channel ->
                channel.sendMessageEmbeds(embed).queue(
                        ignored -> { }, error -> warn("Could not send bank embed for " + town.getName(), error)));
    }

    /** Posts the daily Towny summary in the town's principal Discord channel. */
    public void sendTownDailySummary(Town town) {
        if (areTownChannelsDisabled(town.getName())
                || !isTownFeatureEnabled(town, "newday")
                || !plugin.configuration().getBoolean("messages.DailySummaryEmbed.Enabled", true)) return;
        Resident mayor = town.getMayor();
        Nation nation = nationFor(town);
        Location spawn = town.getSpawnOrNull();
        String moneyFormat = plugin.configuration().getString("messages.DailySummaryEmbed.AmountFormat", "%.2f");
        String balance;
        try {
            balance = String.format(Locale.ROOT, moneyFormat, town.getAccount().getHoldingBalance());
        } catch (RuntimeException ignored) {
            balance = String.format(Locale.ROOT, "%.2f", town.getAccount().getHoldingBalance());
        }
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("town", town.getName());
        placeholders.put("mayor", safe(mayor == null ? null : mayor.getName(), "Nessuno"));
        placeholders.put("nation", safe(nation == null ? null : nation.getName(), "Nessuna"));
        placeholders.put("residents", String.valueOf(town.getNumResidents()));
        placeholders.put("balance", balance);
        placeholders.put("tax", String.valueOf(town.getTaxes()));
        placeholders.put("tax_mode", town.isTaxPercentage() ? "%" : "per residente");
        placeholders.put("spawn_world", spawn == null || spawn.getWorld() == null ? "-" : spawn.getWorld().getName());
        placeholders.put("spawn_x", spawn == null ? "-" : String.valueOf(spawn.getBlockX()));
        placeholders.put("spawn_y", spawn == null ? "-" : String.valueOf(spawn.getBlockY()));
        placeholders.put("spawn_z", spawn == null ? "-" : String.valueOf(spawn.getBlockZ()));
        org.bukkit.OfflinePlayer context = mayor == null ? null : Bukkit.getOfflinePlayer(mayor.getUUID());
        MessageEmbed embed = buildDailySummaryEmbed(placeholders, context);
        ensureRole(TOWN_PREFIX + town.getName(), true).thenCompose(role ->
                ensurePublicTextChannel(town.getName(), townTextCategoryId(), role)).thenAccept(channel -> {
            var message = channel.sendMessageEmbeds(embed);
            if (plugin.configuration().getBoolean("messages.DailySummaryButtons.Enabled", true)) {
                String taxes = plugin.configuration().getString("messages.DailySummaryButtons.TaxesLabel", "Tasse");
                String residents = plugin.configuration().getString("messages.DailySummaryButtons.ResidentsLabel", "Residenti");
                String outposts = plugin.configuration().getString("messages.DailySummaryButtons.OutpostsLabel", "Avamposti");
                message.setActionRow(Button.primary("tdc:taxes:" + town.getName(), taxes),
                        Button.secondary("tdc:residents:" + town.getName() + ":0", residents),
                        Button.success("tdc:outposts:" + town.getName() + ":0", outposts));
            }
            message.queue(ignored -> { }, error -> warn("Could not send daily town summary for " + town.getName(), error));
        });
    }

    /** Registers guild-local commands so their changes are visible immediately. */
    public void registerSlashCommands() {
        if (!plugin.configuration().getBoolean("discord.SlashCommands.Enabled", true)) return;
        Guild guild = guild();
        if (guild == null) return;
        guild.upsertCommand(new CommandData("town", "TownyDiscordChat: città e sincronizzazione")
                .addSubcommands(
                        new SubcommandData("info", "Mostra le informazioni della tua città"),
                        new SubcommandData("sync", "Sincronizza i tuoi ruoli Discord"),
                        new SubcommandData("map", "Mostra la mappa Dynmap della tua citt\u00e0")
                                .addOption(OptionType.STRING, "citta", "Citt\u00e0 da mostrare (solo amministratori Discord)", false),
                        new SubcommandData("notice", "Invia un avviso nel canale della tua città")
                                .addOption(OptionType.STRING, "messaggio", "Testo dell'avviso", true),
                        new SubcommandData("resync", "Sincronizza tutte le risorse Towny (admin Discord)")))
                .queue(ignored -> log("Registered TownyDiscordChat slash commands."),
                        error -> warn("Could not register slash commands", error));
    }

    /** Runs on Bukkit's main thread after a slash-command interaction has been deferred. */
    public String handleTownSlashCommand(String discordId, String subcommand, String message, boolean discordAdministrator) {
        UUID playerId = DiscordSRV.getPlugin().getAccountLinkManager().getLinkedAccounts().get(discordId);
        if (subcommand.equals("resync")) {
            if (!discordAdministrator) return "❌ Questo comando richiede il permesso Discord **Administrator**.";
            synchroniseAllResources();
            synchroniseAllLinkedAccounts();
            return TDCMessages.tr(plugin, "commands.global_sync");
        }
        if (playerId == null) return TDCMessages.tr(plugin, "commands.link_required");
        Town town = townFor(playerId);
        if (town == null) return TDCMessages.tr(plugin, "commands.town_missing");

        return switch (subcommand) {
            case "info" -> "🏘️ **" + town.getName() + "**\n"
                    + "Sindaco: **" + safe(town.getMayor() == null ? null : town.getMayor().getName(), "nessuno") + "**\n"
                    + "Residenti: **" + town.getNumResidents() + "**\n"
                    + "Saldo: **" + String.format(Locale.ROOT, "%.2f", town.getAccount().getHoldingBalance()) + "**";
            case "sync" -> {
                synchronisePlayer(playerId);
                yield "✅ I tuoi ruoli Discord sono stati sincronizzati.";
            }
            case "notice" -> {
                if (!isTownOfficer(town, playerId)) yield TDCMessages.tr(plugin, "commands.notice_denied");
                if (message == null || message.isBlank()) yield TDCMessages.tr(plugin, "commands.notice_missing");
                sendTownNotification(town, "📣 **Avviso città da Discord**\n" + message);
                yield TDCMessages.tr(plugin, "commands.notice_sent");
            }
            default -> "❌ Sottocomando non riconosciuto.";
        };
    }

    /**
     * Creates an overhead Dynmap image for the linked player's town. Dynmap is
     * intentionally accessed through reflection so an absent or updated Dynmap
     * installation can never prevent this plugin from starting.
     */
    public void requestTownDynmapMap(String discordId, String requestedTown, boolean discordAdministrator,
                                     Consumer<DynmapTownMapRenderer.Result> callback) {
        if (!plugin.configuration().getBoolean("dynmap.Enabled", false)) {
            callback.accept(DynmapTownMapRenderer.Result.error(TDCMessages.tr(plugin, "commands.map_disabled")));
            return;
        }
        UUID playerId = DiscordSRV.getPlugin().getAccountLinkManager().getLinkedAccounts().get(discordId);
        if (playerId == null) {
            callback.accept(DynmapTownMapRenderer.Result.error(TDCMessages.tr(plugin, "commands.link_required")));
            return;
        }
        Town linkedTown = townFor(playerId);
        if (linkedTown == null) {
            callback.accept(DynmapTownMapRenderer.Result.error(TDCMessages.tr(plugin, "commands.town_missing")));
            return;
        }
        Town target = linkedTown;
        if (requestedTown != null && !requestedTown.isBlank() && !requestedTown.equalsIgnoreCase(linkedTown.getName())) {
            if (!discordAdministrator) {
                callback.accept(DynmapTownMapRenderer.Result.error(TDCMessages.tr(plugin, "commands.channel_only")));
                return;
            }
            target = TownyUniverse.getInstance().getTown(requestedTown);
            if (target == null) {
                callback.accept(DynmapTownMapRenderer.Result.error(TDCMessages.tr(plugin, "commands.town_not_found", Map.of("town", requestedTown))));
                return;
            }
        }
        Location spawn = target.getSpawnOrNull();
        if (spawn == null || spawn.getWorld() == null) {
            callback.accept(DynmapTownMapRenderer.Result.error("❌ Questa città non ha uno spawn Towny valido."));
            return;
        }
        String dynmapProblem = DynmapTownMapRenderer.validateDynmapWorld(spawn.getWorld().getName());
        if (dynmapProblem != null) {
            callback.accept(DynmapTownMapRenderer.Result.error("❌ " + dynmapProblem));
            return;
        }
        DynmapTownMapRenderer.Request request = new DynmapTownMapRenderer.Request(target.getName(), spawn.getWorld().getName(), spawn.getX(), spawn.getZ());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            DynmapTownMapRenderer.Result result = DynmapTownMapRenderer.render(plugin, request);
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
        });
    }

    public void relayMinecraftMessage(UUID playerId, String playerName, String message) {
        if (!bridgeEnabled("MinecraftToDiscord")) {
            return;
        }
        Town town = townFor(playerId);
        if (town == null || !isTownFeatureEnabled(town, "chat")) {
            return;
        }
        TextChannel channel = publicTextChannel(town.getName(), townTextCategoryId());
        if (channel == null) {
            ensureTownResources(town);
            return;
        }
        String format = plugin.configuration().getString("bridge.MinecraftFormat", "**[MC] %player%:** %message%");
        String output = TDCPlaceholders.resolve(plugin, Bukkit.getOfflinePlayer(playerId), format.replace("%player%", playerName).replace("%message%", message));
        channel.sendMessage(output).queue(
                ignored -> { }, error -> warn("Could not relay Minecraft chat for " + town.getName(), error));
    }

    /**
     * Routes town chat through DiscordSRV so InteractiveChatDiscordSrvAddon can
     * replace [item], [inv] and similar placeholders with its rendered embeds.
     */
    public boolean relayMinecraftMessageThroughDiscordSRV(Player player, String message) {
        if (!bridgeEnabled("MinecraftToDiscord")
                || !plugin.configuration().getBoolean("interactivechat.UseDiscordSRVAddon", true)
                || !plugin.getServer().getPluginManager().isPluginEnabled("InteractiveChatDiscordSrvAddon")) {
            return false;
        }
        Town town = townFor(player.getUniqueId());
        if (town == null || areTownChannelsDisabled(town.getName()) || !isTownFeatureEnabled(town, "chat")) return true;
        TextChannel channel = publicTextChannel(town.getName(), townTextCategoryId());
        if (channel == null) {
            ensureTownResources(town);
            return true;
        }
        String gameChannel = "tdc-town-" + normalise(town.getName());
        DiscordSRV.getPlugin().getChannels().put(gameChannel, channel.getId());
        try {
            DiscordSRV.getPlugin().processChatMessage(player, message, gameChannel, false);
            return true;
        } catch (RuntimeException error) {
            warn("Could not delegate InteractiveChat message for " + town.getName(), error);
            DiscordSRV.getPlugin().getChannels().remove(gameChannel, channel.getId());
            return false;
        }
    }

    /**
     * Towny chat narrows Paper's viewer set to the online residents of the town.
     * Global, nation and other chats have a different audience and are rejected.
     */
    public boolean isTownChatAudience(UUID playerId, Set<UUID> recipients) {
        if (!plugin.configuration().getBoolean("bridge.TownChatOnly", true)) return true;
        Town town = townFor(playerId);
        if (town == null || recipients.isEmpty()) return false;
        Set<UUID> expected = new HashSet<>();
        for (Resident resident : town.getResidents()) {
            Player player = Bukkit.getPlayer(resident.getUUID());
            if (player != null && player.isOnline()) expected.add(player.getUniqueId());
        }
        return !expected.isEmpty() && expected.equals(recipients);
    }

    /** Sends compact Discord embeds for item hovers supplied by InteractiveChat. */
    public void relayInteractiveChatItems(UUID playerId, String playerName, List<ItemPreview> items) {
        if (!bridgeEnabled("MinecraftToDiscord") || items.isEmpty()) return;
        Town town = townFor(playerId);
        if (town == null) return;
        TextChannel channel = publicTextChannel(town.getName(), townTextCategoryId());
        if (channel == null) {
            ensureTownResources(town);
            return;
        }
        for (ItemPreview item : items) {
            channel.sendMessageEmbeds(buildInteractiveChatItemEmbed(playerId, playerName, item)).queue(
                    ignored -> { }, error -> warn("Could not relay InteractiveChat item for " + town.getName(), error));
        }
    }

    /** Called on the Bukkit thread after a Discord message arrives. */
    public void relayDiscordMessage(String discordId, String channelId, String displayName, String message) {
        if (!bridgeEnabled("DiscordToMinecraft")) {
            return;
        }
        Town town = townForPublicChannel(channelId);
        Nation nation = town == null ? nationForPublicChannel(channelId) : null;
        if (town == null && nation == null) {
            return;
        }
        if (town != null && !isTownFeatureEnabled(town, "chat")) return;

        UUID playerId = DiscordSRV.getPlugin().getAccountLinkManager().getLinkedAccounts().get(discordId);
        boolean mustBeLinked = plugin.configuration().getBoolean("bridge.RequireLinkedAccount", true);
        boolean mustBelongToTown = plugin.configuration().getBoolean("bridge.RequireCurrentTown", true);
        boolean belongs = town != null
                ? playerId != null && town.getName().equalsIgnoreCase(nameOf(townFor(playerId)))
                : playerId != null && nation.getName().equalsIgnoreCase(nationFor(playerId) == null ? null : nationFor(playerId).getName());
        if ((mustBeLinked && playerId == null) || (mustBelongToTown && !belongs)) {
            TextChannel channel = guild() == null ? null : guild().getTextChannelById(channelId);
            if (channel != null) {
                channel.sendMessage("⚠️ Collega il tuo account Minecraft e usa il canale della tua città.").queue();
            }
            return;
        }

        String template = plugin.configuration().getString("bridge." + (town == null ? "NationDiscordFormat" : "DiscordFormat"), "&8[&2TDC&8] &c%titolo% &8» &7%usernameds% &8» &f%message%");
        String marker = "__TDC_LITERAL_DISCORD_MESSAGE__";
        String userMarker = "__TDC_LITERAL_DISCORD_USERNAME__";
        String resolvedTemplate = TDCPlaceholders.resolve(plugin, playerId == null ? null : Bukkit.getOfflinePlayer(playerId),
                template.replace("%titolo%", town == null ? nation.getName() : town.getName()).replace("%nazione%", nation == null ? "" : nation.getName()).replace("%usernameds%", userMarker)
                        .replace("%player%", userMarker).replace("%message%", marker));
        Component formatted = LegacyComponentSerializer.legacySection().deserialize(TDCMessages.colour(resolvedTemplate))
                .replaceText(TextReplacementConfig.builder().matchLiteral(userMarker)
                        .replacement(Component.text(displayName, NamedTextColor.GRAY)).build())
                .replaceText(TextReplacementConfig.builder().matchLiteral(marker)
                        .replacement(Component.text(message, NamedTextColor.WHITE)).build());
        Collection<Resident> residents = town != null ? town.getResidents() : nation.getResidents();
        for (Resident resident : residents) {
            Player recipient = Bukkit.getPlayer(resident.getUUID());
            if (recipient != null && recipient.isOnline()) {
                recipient.sendMessage(formatted);
            }
        }
    }

    /** Builds the private details shown by the buttons attached to the NewDay summary. */
    public TownButtonResponse townButtonResponse(String discordId, String channelId, String townName,
                                                   String action, int page, boolean townButtonAdministrator) {
        Town town = TownyUniverse.getInstance().getTown(townName);
        if (town == null) return TownButtonResponse.error("La città non esiste più.");
        TextChannel expectedChannel = publicTextChannel(town.getName(), townTextCategoryId());
        if (expectedChannel == null || !expectedChannel.getId().equals(channelId)) {
            return TownButtonResponse.error("Questo pulsante può essere usato solo nel canale della città.");
        }
        if (!townButtonAdministrator) {
            UUID playerId = DiscordSRV.getPlugin().getAccountLinkManager().getLinkedAccounts().get(discordId);
            Town currentTown = playerId == null ? null : townFor(playerId);
            if (currentTown == null || !currentTown.getName().equalsIgnoreCase(town.getName())) {
                return TownButtonResponse.error("Il tuo account Minecraft collegato non appartiene più a questa città.");
            }
        }
        return switch (action) {
            case "taxes" -> new TownButtonResponse(buildTownTaxesEmbed(town), List.of(), null);
            case "residents" -> buildTownResidentsResponse(town, page);
            case "outposts" -> buildTownOutpostsResponse(town, page);
            default -> TownButtonResponse.error("Pulsante non riconosciuto.");
        };
    }

    /** Applies /town discord <feature> <enable|disable> to the player's current town. */
    public String setTownFeature(Player player, String feature, boolean enabled) {
        Town town = townFor(player.getUniqueId());
        if (town == null) return "&cDevi appartenere a una città.";
        if (!isTownOfficer(town, player.getUniqueId()) && !player.hasPermission("TownyDiscordChat.Admin")) {
            return "&cSolo il sindaco, un vice o un amministratore può modificare queste impostazioni.";
        }
        String canonical = canonicalTownFeature(feature);
        if (canonical == null) return "&cFunzione non valida. Usa &fnewday&c, &fchat &co &fjail&c.";
        plugin.configuration().set(townFeaturePath(town, canonical), enabled);
        plugin.saveConfig();
        String state = enabled ? "abilitata" : "disabilitata";
        String label = switch (canonical) {
            case "NewDay" -> "Riepilogo NewDay";
            case "Chat" -> "Chat bridge";
            case "Jail" -> "Notifiche jail";
            default -> canonical;
        };
        return "&a" + label + " " + state + " per &f" + town.getName() + "&a.";
    }

    public boolean isTownFeatureEnabled(Town town, String feature) {
        String canonical = canonicalTownFeature(feature);
        if (town == null || canonical == null) return true;
        String path = townFeaturePath(town, canonical);
        if (plugin.configuration().contains(path)) return plugin.configuration().getBoolean(path);
        return plugin.configuration().getBoolean("townFeatures.Defaults." + canonical, true);
    }

    private String canonicalTownFeature(String feature) {
        if (feature == null) return null;
        return switch (feature.toLowerCase(Locale.ROOT)) {
            case "newday", "new-day", "daily" -> "NewDay";
            case "chat", "bridge" -> "Chat";
            case "jail", "prigione" -> "Jail";
            default -> null;
        };
    }

    private String townFeaturePath(Town town, String canonicalFeature) {
        return "townFeatures.Towns." + normalise(town.getName()) + "." + canonicalFeature;
    }

    private void renameTownFeatureSettings(String oldName, String newName) {
        String oldPath = "townFeatures.Towns." + normalise(oldName);
        ConfigurationSection existing = plugin.configuration().getConfigurationSection(oldPath);
        if (existing == null) return;
        Map<String, Object> values = new LinkedHashMap<>(existing.getValues(false));
        plugin.configuration().set(oldPath, null);
        String newPath = "townFeatures.Towns." + normalise(newName);
        values.forEach((key, value) -> plugin.configuration().set(newPath + "." + key, value));
        plugin.saveConfig();
    }

    private void deleteTownFeatureSettings(String townName) {
        plugin.configuration().set("townFeatures.Towns." + normalise(townName), null);
        plugin.saveConfig();
    }

    public void renameTown(String oldName, String newName) {
        renameManagedResources(TOWN_PREFIX + oldName, TOWN_PREFIX + newName, oldName, newName, townTextCategoryId(), townVoiceCategoryId());
        renameTownFeatureSettings(oldName, newName);
        removeLegacyStaffChannels(oldName);
        removeLegacyStaffChannels(newName);
    }

    public void renameNation(String oldName, String newName) {
        renameManagedResources(NATION_PREFIX + oldName, NATION_PREFIX + newName, oldName, newName, nationTextCategoryId(), nationVoiceCategoryId());
    }

    public void deleteTown(String townName) {
        deleteManagedResources(TOWN_PREFIX + townName, townName, townTextCategoryId(), townVoiceCategoryId());
        deleteTownFeatureSettings(townName);
        removeLegacyStaffChannels(townName);
    }

    /** Deletes only a town's Discord channels; its role and Towny data are deliberately preserved. */
    public boolean deleteTownChannels(String townName) {
        Town town = TownyUniverse.getInstance().getTown(townName);
        if (town == null) return false;
        Guild guild = guild();
        if (guild == null) return false;
        setTownChannelsDisabled(town.getName(), true);
        deleteText(guild, town.getName(), townTextCategoryId());
        guild.getVoiceChannelsByName(town.getName(), true).stream()
                .filter(channel -> matchesCategory(channel.getParent(), townVoiceCategoryId()))
                .forEach(channel -> channel.delete().queue());
        removeLegacyStaffChannels(town.getName());
        log("Deleted Discord channels for town " + town.getName() + "; the role was kept.");
        return true;
    }

    /** Deletes only the managed town channels for every current Towny town. */
    public int deleteAllTownChannels() {
        int deleted = 0;
        for (Town town : new ArrayList<>(TownyUniverse.getInstance().getTowns())) {
            if (deleteTownChannels(town.getName())) deleted++;
        }
        return deleted;
    }

    /** Re-enables one town's channel provisioning and immediately recreates missing resources. */
    public boolean restoreTownChannels(String townName) {
        Town town = TownyUniverse.getInstance().getTown(townName);
        if (town == null) return false;
        setTownChannelsDisabled(town.getName(), false);
        ensureTownResources(town);
        return true;
    }

    public int restoreAllTownChannels() {
        int restored = 0;
        for (Town town : new ArrayList<>(TownyUniverse.getInstance().getTowns())) {
            setTownChannelsDisabled(town.getName(), false);
            ensureTownResources(town);
            restored++;
        }
        return restored;
    }

    public void deleteNation(String nationName) {
        deleteManagedResources(NATION_PREFIX + nationName, nationName, nationTextCategoryId(), nationVoiceCategoryId());
    }

    private Set<String> expectedRoleNames(UUID playerId) {
        Set<String> expected = new HashSet<>();
        Town town = townFor(playerId);
        if (town != null) {
            expected.add(normalise(TOWN_PREFIX + town.getName()));
            Nation nation = nationFor(town);
            if (nation != null) {
                expected.add(normalise(NATION_PREFIX + nation.getName()));
            }
        }
        return expected;
    }

    private CompletableFuture<Role> ensureRole(String roleName, boolean townRole) {
        Guild guild = guild();
        if (guild == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Discord guild unavailable"));
        }
        Role existing = roleByName(guild, roleName);
        if (existing != null) {
            return CompletableFuture.completedFuture(existing);
        }
        String key = normalise(roleName);
        return pendingRoles.computeIfAbsent(key, ignored -> {
            CompletableFuture<Role> future = new CompletableFuture<>();
            String path = townRole ? "town" : "nation";
            if (!plugin.configuration().getBoolean(path + ".CreateRoleIfNoneExists", true)) {
                future.completeExceptionally(new IllegalStateException("Automatic role creation disabled for " + path));
                return future;
            }
            guild.createRole().setName(roleName).setColor(colour(path + ".RoleCreateColorCode"))
                    .queue(role -> {
                        pendingRoles.remove(key);
                        log("Created Discord role " + roleName);
                        future.complete(role);
                    }, error -> {
                        pendingRoles.remove(key);
                        warn("Could not create Discord role " + roleName, error);
                        future.completeExceptionally(error);
                    });
            return future;
        });
    }

    private CompletableFuture<TextChannel> ensurePublicTextChannel(String name, String categoryId, Role role) {
        return ensureTextChannel(name, categoryId, role, Set.of());
    }

    /** Deletes legacy staff channels created by older releases, regardless of their former category. */
    private void removeLegacyStaffChannels(String townName) {
        Guild guild = guild();
        if (guild != null) {
            Set<String> suffixes = new HashSet<>();
            suffixes.add("-staff");
            suffixes.add(staffSuffix());
            for (String suffix : suffixes) {
                deleteText(guild, townName + suffix, null);
            }
        }
    }

    private CompletableFuture<TextChannel> ensureTextChannel(String name, String categoryId, Role role, Set<String> members) {
        Guild guild = guild();
        if (guild == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Discord guild unavailable"));
        }
        TextChannel existing = publicTextChannel(name, categoryId);
        if (existing != null) {
            updateMemberAccess(existing, members);
            return CompletableFuture.completedFuture(existing);
        }

        String key = normalise(name) + "@" + String.valueOf(categoryId);
        return pendingChannels.computeIfAbsent(key, ignored -> {
            CompletableFuture<TextChannel> future = new CompletableFuture<>();
            long denyView = Permission.VIEW_CHANNEL.getRawValue();
            long allowView = Permission.VIEW_CHANNEL.getRawValue();
            ChannelAction<TextChannel> action = guild.createTextChannel(name)
                    .addRolePermissionOverride(guild.getPublicRole().getIdLong(), 0L, denyView);
            if (role != null) {
                action.addRolePermissionOverride(role.getIdLong(), allowView, 0L);
            }
            Member bot = guild.getSelfMember();
            if (bot != null) {
                action.addMemberPermissionOverride(bot.getIdLong(), allowView, 0L);
            }
            for (String memberId : members) {
                action.addMemberPermissionOverride(Long.parseLong(memberId), allowView, 0L);
            }
            Category category = category(guild, categoryId);
            if (category != null) {
                action.setParent(category);
            }
            action.queue(channel -> {
                pendingChannels.remove(key);
                updateMemberAccess(channel, members);
                log("Created Discord text channel " + name);
                future.complete(channel);
            }, error -> {
                pendingChannels.remove(key);
                warn("Could not create Discord text channel " + name, error);
                future.completeExceptionally(error);
            });
            return future;
        });
    }

    private void ensureVoiceChannel(String name, String categoryId, Role role) {
        Guild guild = guild();
        if (guild == null || guild.getVoiceChannelsByName(name, true).stream().anyMatch(channel -> matchesCategory(channel.getParent(), categoryId))) {
            return;
        }
        long denyView = Permission.VIEW_CHANNEL.getRawValue();
        long allowView = Permission.VIEW_CHANNEL.getRawValue();
        ChannelAction<?> action = guild.createVoiceChannel(name)
                .addRolePermissionOverride(guild.getPublicRole().getIdLong(), 0L, denyView)
                .addRolePermissionOverride(role.getIdLong(), allowView, 0L);
        Member bot = guild.getSelfMember();
        if (bot != null) {
            action.addMemberPermissionOverride(bot.getIdLong(), allowView, 0L);
        }
        Category category = category(guild, categoryId);
        if (category != null) {
            action.setParent(category);
        }
        action.queue(ignored -> log("Created Discord voice channel " + name), error -> warn("Could not create Discord voice channel " + name, error));
    }

    private void updateMemberAccess(TextChannel channel, Set<String> officers) {
        Guild guild = channel.getGuild();
        for (PermissionOverride override : channel.getMemberPermissionOverrides()) {
            Member member = override.getMember();
            if (member != null && !member.equals(guild.getSelfMember()) && !officers.contains(member.getId())) {
                override.delete().queue();
            }
        }
        for (String memberId : officers) {
            Member member = guild.getMemberById(memberId);
            if (member != null) {
                channel.upsertPermissionOverride(member).setAllow(Permission.VIEW_CHANNEL).queue();
            }
        }
    }

    private void addRoleIfMissing(Guild guild, Member member, Role role) {
        if (!member.getRoles().contains(role)) {
            guild.addRoleToMember(member, role).queue(
                    ignored -> log("Added Discord role " + role.getName() + " to " + member.getEffectiveName()),
                    error -> warn("Could not add Discord role " + role.getName(), error));
        }
    }

    private void renameManagedResources(String oldRole, String newRole, String oldName, String newName,
                                        String textCategory, String voiceCategory) {
        Guild guild = guild();
        if (guild == null) return;
        Role role = roleByName(guild, oldRole);
        if (role != null) {
            // Rename channels by the existing role permission first. This also
            // works when another plugin has already changed the channel name.
            String roleId = role.getId();
            guild.getTextChannels().stream()
                    .filter(channel -> matchesCategory(channel.getParent(), textCategory))
                    .filter(channel -> hasRoleOverride(channel, roleId))
                    .forEach(channel -> channel.getManager().setName(newName).queue());
            guild.getVoiceChannels().stream()
                    .filter(channel -> matchesCategory(channel.getParent(), voiceCategory))
                    .filter(channel -> hasRoleOverride(channel, roleId))
                    .forEach(channel -> channel.getManager().setName(newName).queue());
            role.getManager().setName(newRole).queue();
        }
        renameText(guild, oldName, newName, textCategory);
        renameVoice(guild, oldName, newName, voiceCategory);
    }

    /** Relays nation chat to the matching private nation channel. */
    public void relayNationMinecraftMessage(Player player, String playerName, String message) {
        if (!bridgeEnabled("MinecraftToDiscord")) return;
        Nation nation = nationFor(player.getUniqueId());
        if (nation == null) return;
        TextChannel channel = publicTextChannel(nation.getName(), nationTextCategoryId());
        if (channel == null) { ensureNationResources(nation); return; }
        String format = plugin.configuration().getString("bridge.NationMinecraftFormat", "**[MC] %player%:** %message%");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", playerName); values.put("message", message); values.put("nazione", nation.getName()); values.put("nation", nation.getName());
        channel.sendMessage(configText(format, values, player)).queue();
    }

    public boolean relayNationMinecraftMessageThroughDiscordSRV(Player player, String message) {
        if (!bridgeEnabled("MinecraftToDiscord") || !plugin.configuration().getBoolean("interactivechat.UseDiscordSRVAddon", true)
                || !plugin.getServer().getPluginManager().isPluginEnabled("InteractiveChatDiscordSrvAddon")) return false;
        Nation nation = nationFor(player.getUniqueId());
        if (nation == null) return true;
        TextChannel channel = publicTextChannel(nation.getName(), nationTextCategoryId());
        if (channel == null) { ensureNationResources(nation); return true; }
        String gameChannel = "tdc-nation-" + normalise(nation.getName());
        DiscordSRV.getPlugin().getChannels().put(gameChannel, channel.getId());
        try { DiscordSRV.getPlugin().processChatMessage(player, message, gameChannel, false); return true; }
        catch (RuntimeException error) { warn("Could not delegate nation chat for " + nation.getName(), error); return false; }
    }

    private boolean hasRoleOverride(github.scarsz.discordsrv.dependencies.jda.api.entities.GuildChannel channel, String roleId) {
        return channel.getRolePermissionOverrides().stream()
                .map(PermissionOverride::getRole).filter(java.util.Objects::nonNull)
                .anyMatch(role -> role.getId().equals(roleId));
    }

    private void deleteManagedResources(String roleName, String name, String textCategory, String voiceCategory) {
        Guild guild = guild();
        if (guild == null) return;
        Role role = roleByName(guild, roleName);
        if (role != null) role.delete().queue();
        deleteText(guild, name, textCategory);
        guild.getVoiceChannelsByName(name, true).stream().filter(channel -> matchesCategory(channel.getParent(), voiceCategory)).forEach(channel -> channel.delete().queue());
    }

    private void renameText(Guild guild, String oldName, String newName, String categoryId) {
        guild.getTextChannelsByName(oldName, true).stream().filter(channel -> matchesCategory(channel.getParent(), categoryId)).forEach(channel -> channel.getManager().setName(newName).queue());
    }

    private void renameVoice(Guild guild, String oldName, String newName, String categoryId) {
        guild.getVoiceChannelsByName(oldName, true).stream().filter(channel -> matchesCategory(channel.getParent(), categoryId)).forEach(channel -> channel.getManager().setName(newName).queue());
    }

    private void deleteText(Guild guild, String name, String categoryId) {
        guild.getTextChannelsByName(name, true).stream().filter(channel -> matchesCategory(channel.getParent(), categoryId)).forEach(channel -> channel.delete().queue());
    }

    private Town townForPublicChannel(String channelId) {
        for (Town town : TownyUniverse.getInstance().getTowns()) {
            TextChannel channel = publicTextChannel(town.getName(), townTextCategoryId());
            if (channel != null && channel.getId().equals(channelId)) {
                return town;
            }
        }
        return null;
    }

    private Nation nationForPublicChannel(String channelId) {
        for (Nation nation : TownyUniverse.getInstance().getNations()) {
            TextChannel channel = publicTextChannel(nation.getName(), nationTextCategoryId());
            if (channel != null && channel.getId().equals(channelId)) return nation;
        }
        return null;
    }

    private TextChannel publicTextChannel(String name, String categoryId) {
        Guild guild = guild();
        if (guild == null) return null;
        return guild.getTextChannelsByName(name, true).stream()
                .filter(channel -> matchesCategory(channel.getParent(), categoryId)).findFirst().orElse(null);
    }

    private Town townFor(UUID playerId) {
        Resident resident = TownyUniverse.getInstance().getResident(playerId);
        if (resident == null || !resident.hasTown()) return null;
        try {
            return resident.getTown();
        } catch (NotRegisteredException ignored) {
            return null;
        }
    }

    private Nation nationFor(Town town) {
        if (town == null || !town.hasNation()) return null;
        try {
            return town.getNation();
        } catch (NotRegisteredException ignored) {
            return null;
        }
    }

    private Nation nationFor(UUID playerId) {
        Town town = townFor(playerId);
        return nationFor(town);
    }

    /**
     * Towny changed its assistant API between supported releases. Prefer the old
     * direct accessor, then fall back to its rank API without hard-linking this
     * plugin to either implementation.
     */
    @SuppressWarnings("unchecked")
    private Collection<Resident> assistantResidents(Town town) {
        try {
            Method method = town.getClass().getMethod("getAssistants");
            Object result = method.invoke(town);
            if (result instanceof Collection<?> collection) {
                return collection.stream().filter(Resident.class::isInstance).map(Resident.class::cast).toList();
            }
        } catch (ReflectiveOperationException ignored) {
            // Towny 0.103+ no longer exposes this exact accessor.
        }
        for (String rank : List.of("assistant", "vice", "deputy")) {
            try {
                Method method = town.getClass().getMethod("getRank", String.class);
                Object result = method.invoke(town, rank);
                if (result instanceof Collection<?> collection) {
                    return collection.stream().filter(Resident.class::isInstance).map(Resident.class::cast).toList();
                }
            } catch (ReflectiveOperationException ignored) {
                // Try the next possible rank name/API.
            }
        }
        return List.of();
    }

    private boolean isTownOfficer(Town town, UUID playerId) {
        Resident mayor = town.getMayor();
        if (mayor != null && mayor.getUUID().equals(playerId)) return true;
        return assistantResidents(town).stream().anyMatch(resident -> resident.getUUID().equals(playerId));
    }

    private MessageEmbed buildBankEmbed(Map<String, String> placeholders, org.bukkit.OfflinePlayer context) {
        ConfigurationSection section = plugin.configuration().getConfigurationSection("messages.BankEmbed");
        EmbedBuilder embed = new EmbedBuilder();
        if (section == null) {
            return embed.setTitle("🏦 Movimento banca — " + placeholders.get("town"))
                    .addField("Operazione", placeholders.get("type"), true)
                    .addField("Importo", placeholders.get("amount"), true)
                    .addField("Saldo", placeholders.get("balance"), true)
                    .addField("Eseguita da", placeholders.get("actor"), false).build();
        }
        String title = configText(section.getString("Title", ""), placeholders, context);
        String description = configText(section.getString("Description", ""), placeholders, context);
        if (!title.isBlank()) embed.setTitle(title);
        if (!description.isBlank()) embed.setDescription(description);
        embed.setColor(colour("messages.BankEmbed.Color"));
        if (section.getBoolean("Timestamp", true)) embed.setTimestamp(Instant.now());

        String footerText = configText(section.getString("Footer.Text", ""), placeholders, context);
        String footerIcon = section.getString("Footer.IconUrl", "");
        if (!footerText.isBlank()) embed.setFooter(footerText, blankToNull(footerIcon));
        String authorName = configText(section.getString("Author.Name", ""), placeholders, context);
        if (!authorName.isBlank()) embed.setAuthor(authorName, blankToNull(section.getString("Author.Url", "")), blankToNull(section.getString("Author.IconUrl", "")));
        String thumbnail = blankToNull(section.getString("ThumbnailUrl", ""));
        String image = blankToNull(section.getString("ImageUrl", ""));
        if (thumbnail != null) embed.setThumbnail(thumbnail);
        if (image != null) embed.setImage(image);

        ConfigurationSection fields = section.getConfigurationSection("Fields");
        if (fields != null) {
            for (String key : fields.getKeys(false)) {
                String path = "Fields." + key;
                String name = configText(section.getString(path + ".Name", key), placeholders, context);
                String value = configText(section.getString(path + ".Value", "-"), placeholders, context);
                if (!name.isBlank() && !value.isBlank()) {
                    embed.addField(name, value, section.getBoolean(path + ".Inline", false));
                }
            }
        }
        return embed.build();
    }

    private MessageEmbed buildDailySummaryEmbed(Map<String, String> placeholders, org.bukkit.OfflinePlayer context) {
        ConfigurationSection section = plugin.configuration().getConfigurationSection("messages.DailySummaryEmbed");
        EmbedBuilder embed = new EmbedBuilder();
        if (section == null) {
            return embed.setTitle("📊 Riepilogo giornaliero — " + placeholders.get("town"))
                    .addField("Sindaco", placeholders.get("mayor"), true)
                    .addField("Residenti", placeholders.get("residents"), true)
                    .addField("Saldo", placeholders.get("balance"), true)
                    .build();
        }
        String title = configText(section.getString("Title", ""), placeholders, context);
        String description = configText(section.getString("Description", ""), placeholders, context);
        if (!title.isBlank()) embed.setTitle(title);
        if (!description.isBlank()) embed.setDescription(description);
        embed.setColor(colour("messages.DailySummaryEmbed.Color"));
        if (section.getBoolean("Timestamp", true)) embed.setTimestamp(Instant.now());

        String footerText = configText(section.getString("Footer.Text", ""), placeholders, context);
        String footerIcon = section.getString("Footer.IconUrl", "");
        if (!footerText.isBlank()) embed.setFooter(footerText, blankToNull(footerIcon));
        String authorName = configText(section.getString("Author.Name", ""), placeholders, context);
        if (!authorName.isBlank()) embed.setAuthor(authorName, blankToNull(section.getString("Author.Url", "")), blankToNull(section.getString("Author.IconUrl", "")));
        String thumbnail = blankToNull(section.getString("ThumbnailUrl", ""));
        String image = blankToNull(section.getString("ImageUrl", ""));
        if (thumbnail != null) embed.setThumbnail(thumbnail);
        if (image != null) embed.setImage(image);

        ConfigurationSection fields = section.getConfigurationSection("Fields");
        if (fields != null) {
            for (String key : fields.getKeys(false)) {
                String path = "Fields." + key;
                String name = configText(section.getString(path + ".Name", key), placeholders, context);
                String value = configText(section.getString(path + ".Value", "-"), placeholders, context);
                if (!name.isBlank() && !value.isBlank()) {
                    embed.addField(name, value, section.getBoolean(path + ".Inline", false));
                }
            }
        }
        return embed.build();
    }

    private MessageEmbed buildTownTaxesEmbed(Town town) {
        Nation nation = nationFor(town);
        String root = "messages.TaxDetailsEmbed";
        String amountFormat = plugin.configuration().getString(root + ".AmountFormat", "%.2f");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("town", town.getName());
        values.put("balance", formatMoney(town.getAccount().getHoldingBalance(), amountFormat));
        values.put("upkeep", formatMoney(townUpkeep(town), amountFormat));
        values.put("resident_tax", formatMoney(town.getTaxes(), amountFormat));
        values.put("resident_tax_mode", town.isTaxPercentage() ? "%" : "fissa");
        values.put("plot_tax", formatMoney(town.getPlotTax(), amountFormat));
        values.put("commercial_tax", formatMoney(town.getCommercialPlotTax(), amountFormat));
        values.put("embassy_tax", formatMoney(town.getEmbassyPlotTax(), amountFormat));
        values.put("nation_tax", formatMoney(nation == null ? 0D : nation.getTaxes(), amountFormat));
        values.put("nation", nation == null ? "Nessuna" : nation.getName());
        Resident mayor = town.getMayor();
        org.bukkit.OfflinePlayer context = mayor == null ? null : Bukkit.getOfflinePlayer(mayor.getUUID());
        return buildConfiguredEmbed(root, values, context, "💰 Tasse — " + town.getName());
    }

    private TownButtonResponse buildTownResidentsResponse(Town town, int requestedPage) {
        String root = "messages.ResidentsEmbed";
        int pageSize = Math.max(5, Math.min(20, plugin.configuration().getInt(root + ".PageSize", 12)));
        List<Resident> residents = new ArrayList<>(town.getResidents());
        residents.sort(Comparator.<Resident>comparingInt(resident -> town.isMayor(resident) ? 0 : 1)
                .thenComparingInt(resident -> Bukkit.getPlayer(resident.getUUID()) != null ? 0 : 1)
                .thenComparing(Resident::getName, String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (residents.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = page * pageSize;
        int to = Math.min(residents.size(), from + pageSize);
        String entryTemplate = plugin.configuration().getString(root + ".EntryFormat",
                "• **%resident%** — %roles%\n  Ultimo accesso: %last_seen%");
        List<String> entries = new ArrayList<>();
        for (Resident resident : residents.subList(from, to)) {
            List<String> roles = new ArrayList<>();
            if (town.isMayor(resident)) {
                roles.add(plugin.configuration().getString(root + ".MayorLabel", "Sindaco"));
            }
            roles.addAll(resident.getTownRanks());
            if (roles.isEmpty()) roles.add(plugin.configuration().getString(root + ".ResidentLabel", "Residente"));
            Player online = Bukkit.getPlayer(resident.getUUID());
            String lastSeen;
            if (online != null && online.isOnline()) {
                lastSeen = plugin.configuration().getString(root + ".OnlineText", "🟢 Online ora");
            } else if (resident.getLastOnline() > 0L) {
                long timestamp = resident.getLastOnline() > 10_000_000_000L
                        ? resident.getLastOnline() / 1000L : resident.getLastOnline();
                lastSeen = plugin.configuration().getString(root + ".LastSeenFormat", "<t:%timestamp%:F> (<t:%timestamp%:R>)")
                        .replace("%timestamp%", String.valueOf(timestamp));
            } else {
                lastSeen = plugin.configuration().getString(root + ".NeverSeenText", "Mai");
            }
            Map<String, String> entryValues = new LinkedHashMap<>();
            entryValues.put("resident", resident.getName());
            entryValues.put("roles", String.join(", ", roles));
            entryValues.put("last_seen", lastSeen);
            entries.add(configText(entryTemplate, entryValues, Bukkit.getOfflinePlayer(resident.getUUID())));
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("town", town.getName());
        values.put("entries", entries.isEmpty() ? "Nessun residente." : String.join("\n", entries));
        values.put("page", String.valueOf(page + 1));
        values.put("pages", String.valueOf(pages));
        values.put("residents", String.valueOf(residents.size()));
        Resident mayor = town.getMayor();
        org.bukkit.OfflinePlayer context = mayor == null ? null : Bukkit.getOfflinePlayer(mayor.getUUID());
        MessageEmbed embed = buildConfiguredEmbed(root, values, context, "👥 Residenti — " + town.getName());
        String previous = plugin.configuration().getString(root + ".PreviousLabel", "◀ Precedente");
        String next = plugin.configuration().getString(root + ".NextLabel", "Successiva ▶");
        List<Button> buttons = List.of(
                Button.secondary("tdc:residents:" + town.getName() + ":" + Math.max(0, page - 1) + ":previous", previous).withDisabled(page == 0),
                Button.secondary("tdc:residents:" + town.getName() + ":" + Math.min(pages - 1, page + 1) + ":next", next).withDisabled(page >= pages - 1));
        return new TownButtonResponse(embed, buttons, null);
    }

    private TownButtonResponse buildTownOutpostsResponse(Town town, int requestedPage) {
        String root = "messages.OutpostsEmbed";
        int pageSize = Math.max(5, Math.min(20, plugin.configuration().getInt(root + ".PageSize", 10)));
        List<String> entries = new ArrayList<>();
        for (TownBlock block : town.getTownBlocks()) {
            if (!block.isOutpost()) continue;
            String group = block.hasPlotObjectGroup() && block.getPlotObjectGroup() != null
                    ? block.getPlotObjectGroup().getName() : "Senza gruppo";
            String displayName = group.equals("Senza gruppo") ? block.getName() : group;
            String world = block.getWorld() == null ? "?" : block.getWorld().getName();
            Map<String, String> values = new LinkedHashMap<>();
            values.put("outpost", displayName);
            values.put("group", group);
            values.put("world", world);
            values.put("x", String.valueOf(block.getX()));
            values.put("z", String.valueOf(block.getZ()));
            String template = plugin.configuration().getString(root + ".EntryFormat",
                    "• **%outpost%** — %group% · `%world% %x%, %z%`");
            entries.add(configText(template, values, null));
        }
        entries.sort(String.CASE_INSENSITIVE_ORDER);
        int pages = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = page * pageSize;
        int to = Math.min(entries.size(), from + pageSize);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("town", town.getName());
        values.put("entries", entries.isEmpty() ? plugin.configuration().getString(root + ".EmptyText", "Nessun avamposto registrato.") : String.join("\n", entries.subList(from, to)));
        values.put("page", String.valueOf(page + 1));
        values.put("pages", String.valueOf(pages));
        values.put("outposts", String.valueOf(entries.size()));
        Resident mayor = town.getMayor();
        MessageEmbed embed = buildConfiguredEmbed(root, values,
                mayor == null ? null : Bukkit.getOfflinePlayer(mayor.getUUID()), "🏕️ Avamposti — " + town.getName());
        String previous = plugin.configuration().getString(root + ".PreviousLabel", "◀ Precedente");
        String next = plugin.configuration().getString(root + ".NextLabel", "Successiva ▶");
        List<Button> buttons = List.of(
                Button.secondary("tdc:outposts:" + town.getName() + ":" + Math.max(0, page - 1) + ":previous", previous).withDisabled(page == 0),
                Button.secondary("tdc:outposts:" + town.getName() + ":" + Math.min(pages - 1, page + 1) + ":next", next).withDisabled(page >= pages - 1));
        return new TownButtonResponse(embed, buttons, null);
    }

    private MessageEmbed buildConfiguredEmbed(String root, Map<String, String> values,
                                               org.bukkit.OfflinePlayer context, String fallbackTitle) {
        ConfigurationSection section = plugin.configuration().getConfigurationSection(root);
        EmbedBuilder embed = new EmbedBuilder();
        if (section == null) return embed.setTitle(fallbackTitle).build();
        String title = configText(section.getString("Title", fallbackTitle), values, context);
        String description = configText(section.getString("Description", ""), values, context);
        if (!title.isBlank()) embed.setTitle(title);
        if (!description.isBlank()) embed.setDescription(description);
        embed.setColor(colour(root + ".Color"));
        if (section.getBoolean("Timestamp", false)) embed.setTimestamp(Instant.now());
        String footer = configText(section.getString("Footer.Text", ""), values, context);
        if (!footer.isBlank()) embed.setFooter(footer, blankToNull(section.getString("Footer.IconUrl", "")));
        ConfigurationSection fields = section.getConfigurationSection("Fields");
        if (fields != null) {
            for (String key : fields.getKeys(false)) {
                String path = "Fields." + key;
                String name = configText(section.getString(path + ".Name", key), values, context);
                String value = configText(section.getString(path + ".Value", "-"), values, context);
                if (!name.isBlank() && !value.isBlank()) embed.addField(name, value, section.getBoolean(path + ".Inline", false));
            }
        }
        return embed.build();
    }

    private String formatMoney(double amount, String format) {
        try {
            return String.format(Locale.ROOT, format, amount);
        } catch (RuntimeException ignored) {
            return String.format(Locale.ROOT, "%.2f", amount);
        }
    }

    /** Towny has changed this helper's binary signature in the past, so resolve it safely. */
    private double townUpkeep(Town town) {
        try {
            Class<?> settings = Class.forName("com.palmergames.bukkit.towny.TownySettings");
            Object value = settings.getMethod("getTownUpkeepCost", Town.class).invoke(null, town);
            return value instanceof Number number ? number.doubleValue() : 0D;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return 0D;
        }
    }

    private MessageEmbed buildInteractiveChatItemEmbed(UUID playerId, String playerName, ItemPreview item) {
        ConfigurationSection section = plugin.configuration().getConfigurationSection("interactivechat.ItemEmbed");
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", playerName);
        placeholders.put("item", item.itemKey());
        placeholders.put("amount", String.valueOf(item.amount()));
        placeholders.put("display_name", item.displayName());
        org.bukkit.OfflinePlayer context = Bukkit.getOfflinePlayer(playerId);
        EmbedBuilder embed = new EmbedBuilder();
        if (section == null) {
            return embed.setTitle("🧰 Oggetto mostrato da " + playerName)
                    .setDescription(item.displayName())
                    .addField("Materiale", item.itemKey(), true)
                    .addField("Quantità", String.valueOf(item.amount()), true).build();
        }
        String title = configText(section.getString("Title", ""), placeholders, context);
        String description = configText(section.getString("Description", ""), placeholders, context);
        if (!title.isBlank()) embed.setTitle(title);
        if (!description.isBlank()) embed.setDescription(description);
        embed.setColor(colour("interactivechat.ItemEmbed.Color"));
        if (section.getBoolean("Timestamp", false)) embed.setTimestamp(Instant.now());
        String footer = configText(section.getString("Footer.Text", ""), placeholders, context);
        if (!footer.isBlank()) embed.setFooter(footer, blankToNull(section.getString("Footer.IconUrl", "")));
        ConfigurationSection fields = section.getConfigurationSection("Fields");
        if (fields != null) {
            for (String key : fields.getKeys(false)) {
                String path = "Fields." + key;
                String name = configText(section.getString(path + ".Name", key), placeholders, context);
                String value = configText(section.getString(path + ".Value", "-"), placeholders, context);
                if (!name.isBlank() && !value.isBlank()) embed.addField(name, value, section.getBoolean(path + ".Inline", false));
            }
        }
        return embed.build();
    }

    private String replacePlaceholders(String input, Map<String, String> values) {
        String output = input == null ? "" : input;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            output = output.replace("%" + entry.getKey() + "%", entry.getValue());
            output = output.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return output;
    }

    private String configText(String input, Map<String, String> values, org.bukkit.OfflinePlayer context) {
        return TDCPlaceholders.resolve(plugin, context, replacePlaceholders(input, values));
    }

    private String safe(String input, String fallback) { return input == null || input.isBlank() || input.equals("null") ? fallback : input; }
    private String blankToNull(String input) { return input == null || input.isBlank() ? null : input; }

    private Guild guild() {
        return DiscordSRV.getPlugin() == null ? null : DiscordSRV.getPlugin().getMainGuild();
    }

    private Role roleByName(Guild guild, String name) {
        return guild.getRolesByName(name, true).stream().findFirst().orElse(null);
    }

    private boolean isManagedRole(Role role) {
        String name = normalise(role.getName());
        return name.startsWith(TOWN_PREFIX) || name.startsWith(NATION_PREFIX);
    }

    private boolean bridgeEnabled(String direction) {
        return plugin.configuration().getBoolean("bridge.Enabled", true) && plugin.configuration().getBoolean("bridge." + direction, true);
    }

    private String townTextCategoryId() { return categoryId("town.UseCategoryForText", "town.TextCategoryId"); }
    private String townVoiceCategoryId() { return categoryId("town.UseCategoryForVoice", "town.VoiceCategoryId"); }
    private String nationTextCategoryId() { return categoryId("nation.UseCategoryForText", "nation.TextCategoryId"); }
    private String nationVoiceCategoryId() { return categoryId("nation.UseCategoryForVoice", "nation.VoiceCategoryId"); }

    private String categoryId(String enabledPath, String idPath) {
        if (!plugin.configuration().getBoolean(enabledPath, true)) return null;
        String value = plugin.configuration().getString(idPath, "0");
        return value == null || value.equals("0") || value.isBlank() ? null : value;
    }

    private Category category(Guild guild, String id) {
        if (id == null) return null;
        Category category = guild.getCategoryById(id);
        if (category == null) warn("Configured Discord category " + id + " does not exist; creating the channel without a category.", null);
        return category;
    }

    private boolean matchesCategory(Category parent, String expectedId) {
        return expectedId == null || (parent != null && parent.getId().equals(expectedId));
    }

    private Color colour(String path) {
        try {
            return Color.decode(plugin.configuration().getString(path, "0x808080"));
        } catch (NumberFormatException ignored) {
            return Color.GRAY;
        }
    }

    /** The old setting is only read once more to remove channels created by a previous release. */
    private String staffSuffix() { return plugin.configuration().getString("town.StaffChannel.NameSuffix", "-staff"); }
    private boolean areTownChannelsDisabled(String townName) {
        return plugin.configuration().getStringList("channels.DisabledTowns").stream().anyMatch(name -> name.equalsIgnoreCase(townName));
    }
    private void setTownChannelsDisabled(String townName, boolean disabled) {
        List<String> names = new ArrayList<>(plugin.configuration().getStringList("channels.DisabledTowns"));
        names.removeIf(name -> name.equalsIgnoreCase(townName));
        if (disabled) names.add(townName);
        plugin.configuration().set("channels.DisabledTowns", names);
        plugin.saveConfig();
    }
    private String normalise(String input) { return input.toLowerCase(Locale.ROOT); }
    private String nameOf(Town town) { return town == null ? null : town.getName(); }

    /** An item detected in an Adventure SHOW_ITEM hover component. */
    public record ItemPreview(String itemKey, int amount, String displayName) {
    }

    public record TownButtonResponse(MessageEmbed embed, List<Button> buttons, String error) {
        public static TownButtonResponse error(String message) {
            return new TownButtonResponse(null, List.of(), "⚠️ " + message);
        }
    }

    public record TownFallSnapshot(String town, String mayor, UUID mayorId, String nation,
                                   List<String> citizens, int residentCount, double balance,
                                   double townValue, double residentWealth, double averageWealth,
                                   int plots, double tax, double upkeep, boolean ruined, boolean bankrupt) {
    }

    private void log(String message) { plugin.getLogger().info(message); }
    private void warn(String message, Throwable error) {
        if (error == null) plugin.getLogger().warning(message);
        else plugin.getLogger().warning(message + ": " + error.getMessage());
    }
}
