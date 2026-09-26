package com.TownyDiscordChat.TownyDiscordChat.Listeners;

import com.TownyDiscordChat.TownyDiscordChat.Main;
import com.TownyDiscordChat.TownyDiscordChat.TDCMessages;
import com.TownyDiscordChat.TownyDiscordChat.TDCManager.TownFallSnapshot;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.event.DeleteNationEvent;
import com.palmergames.bukkit.towny.event.DeleteTownEvent;
import com.palmergames.bukkit.towny.event.NationAddTownEvent;
import com.palmergames.bukkit.towny.event.NationRemoveTownEvent;
import com.palmergames.bukkit.towny.event.NewDayEvent;
import com.palmergames.bukkit.towny.event.NewTownEvent;
import com.palmergames.bukkit.towny.event.PreNewDayEvent;
import com.palmergames.bukkit.towny.event.RenameNationEvent;
import com.palmergames.bukkit.towny.event.RenameTownEvent;
import com.palmergames.bukkit.towny.event.TownAddResidentEvent;
import com.palmergames.bukkit.towny.event.TownRemoveResidentEvent;
import com.palmergames.bukkit.towny.event.town.TownMayorChangeEvent;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Translatable;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TDCTownyListener implements Listener {
    private final Main plugin;
    private final Map<String, ActorHint> pendingManualBankActions = new ConcurrentHashMap<>();
    private final Map<String, ActorHint> pendingRankActions = new ConcurrentHashMap<>();
    private final Map<String, TownFallSnapshot> preNewDayTownSnapshots = new ConcurrentHashMap<>();
    private volatile long taxCollectionWindowEnds;
    private boolean outlawEventsAvailable;

    public TDCTownyListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPreNewDay(PreNewDayEvent event) {
        preNewDayTownSnapshots.clear();
        for (Town town : TownyUniverse.getInstance().getTowns()) {
            preNewDayTownSnapshots.put(town.getName().toLowerCase(Locale.ROOT),
                    plugin.manager().captureTownFallSnapshot(town));
        }
    }

    @EventHandler
    public void onNewDay(NewDayEvent event) {
        // Some Towny versions expose tax deposits without a player or a reason.
        // Keep a small window so those deposits are labelled accurately.
        taxCollectionWindowEnds = System.currentTimeMillis() + 15_000L;
        plugin.manager().synchroniseAllResources();
        // Discord resource creation is asynchronous; reconcile memberships once roles are available.
        plugin.getServer().getScheduler().runTaskLater(plugin, plugin.manager()::synchroniseAllLinkedAccounts, 20L * 15L);
        Map<String, TownFallSnapshot> snapshots = new LinkedHashMap<>(preNewDayTownSnapshots);
        List<String> bankrupted = List.copyOf(event.getBankruptedTowns());
        List<String> fallen = List.copyOf(event.getFallenTowns());
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> publishGlobalTownFallReports(snapshots, bankrupted, fallen), 40L);
        plugin.getServer().getScheduler().runTaskLater(plugin, this::publishDailyReports, 20L * 20L);
    }

    @EventHandler
    public void onTownCreated(NewTownEvent event) {
        Town town = event.getTown();
        plugin.manager().ensureTownResources(town);
        Resident mayor = town.getMayor();
        String mayorName = mayor == null ? "Qualcuno" : mayor.getName();
        String townyMessage;
        try {
            townyMessage = Translatable.of("msg_new_town", mayorName, town.getName()).translate();
        } catch (RuntimeException | LinkageError ignored) {
            townyMessage = mayorName + " founded a new town called " + town.getName();
        }
        plugin.manager().sendTownCreatedMessage(town, TDCMessages.strip(townyMessage));
        if (mayor != null) synchroniseNextTick(mayor.getUUID());
    }

    /** Adds a namespaced Towny subcommand without replacing Towny's /town command. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTownDiscordSettingsCommand(PlayerCommandPreprocessEvent event) {
        String[] words = event.getMessage().trim().split("\\s+");
        if (words.length < 2 || !words[0].matches("(?i)/(?:town|t|towny:town)")
                || !words[1].equalsIgnoreCase("discord")) return;
        event.setCancelled(true);
        if (words.length != 4) {
            TDCMessages.send(event.getPlayer(), plugin,
                    "&eUso: &f/" + words[0].substring(1) + " discord <newday|chat|jail> <enable|disable>");
            return;
        }
        boolean enable = words[3].equalsIgnoreCase("enable") || words[3].equalsIgnoreCase("abilita")
                || words[3].equalsIgnoreCase("on");
        boolean disable = words[3].equalsIgnoreCase("disable") || words[3].equalsIgnoreCase("disabilita")
                || words[3].equalsIgnoreCase("off");
        if (!enable && !disable) {
            TDCMessages.send(event.getPlayer(), plugin, "&cInvalid state. Use &fenable &cor &fdisable&c.");
            return;
        }
        TDCMessages.send(event.getPlayer(), plugin,
                plugin.manager().setTownFeature(event.getPlayer(), words[2], enable));
    }

    /** Remembers the actor of /town deposit and /town withdraw until Towny emits its generic bank event. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTownBankCommand(PlayerCommandPreprocessEvent event) {
        String[] words = event.getMessage().trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length < 2) return;
        boolean townCommand = words[0].matches("/(?:town|t|towny:town)");
        boolean bankAction = false;
        for (String word : words) {
            if (word.equals("deposit") || word.equals("withdraw")) {
                bankAction = true;
                break;
            }
        }
        if (!townCommand || !bankAction) return;
        Resident resident = TownyUniverse.getInstance().getResident(event.getPlayer().getUniqueId());
        Town town = resident == null ? null : resident.getTownOrNull();
        if (town == null) return;
        String reason = java.util.Arrays.asList(words).contains("withdraw") ? "Manual withdrawal" : "Deposito manuale";
        pendingManualBankActions.put(town.getName().toLowerCase(Locale.ROOT),
                new ActorHint(event.getPlayer().getName(), reason, System.currentTimeMillis() + 10_000L));
    }

    /** Towny rank events identify the target resident, not the command sender. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTownRankCommand(PlayerCommandPreprocessEvent event) {
        String[] words = event.getMessage().trim().split("\\s+");
        if (words.length < 5 || !words[0].matches("(?i)/(?:town|t|towny:town)")) return;
        if (!words[1].equalsIgnoreCase("rank")) return;
        boolean add = words[2].equalsIgnoreCase("add");
        boolean remove = words[2].equalsIgnoreCase("remove") || words[2].equalsIgnoreCase("del");
        if (!add && !remove) return;
        Resident sender = TownyUniverse.getInstance().getResident(event.getPlayer().getUniqueId());
        Town town = sender == null ? null : sender.getTownOrNull();
        if (town == null) return;
        String key = rankActionKey(town.getName(), words[3], words[4]);
        pendingRankActions.put(key, new ActorHint(event.getPlayer().getName(), add ? "add" : "remove",
                System.currentTimeMillis() + 10_000L));
    }

    @EventHandler
    public void onTownMemberAdded(TownAddResidentEvent event) {
        Town town = event.getTown();
        plugin.manager().refreshTown(town);
        plugin.manager().refreshTownStaff(town);
        plugin.manager().sendTownNotification(town, TDCMessages.tr(plugin, "events.resident_joined", Map.of("resident", event.getResident().getName())));
        synchroniseNextTick(event.getResident().getUUID());
    }

    @EventHandler
    public void onTownMemberRemoved(TownRemoveResidentEvent event) {
        Town town = event.getTown();
        plugin.manager().refreshTownStaff(town);
        plugin.manager().sendTownNotification(town, TDCMessages.tr(plugin, "events.resident_left", Map.of("resident", event.getResident().getName())));
        synchroniseNextTick(event.getResident().getUUID());
    }

    @EventHandler
    public void onTownJoinsNation(NationAddTownEvent event) {
        plugin.manager().refreshNation(event.getNation());
        plugin.manager().sendTownNotification(event.getTown(), TDCMessages.tr(plugin, "events.nation_joined", Map.of("nation", event.getNation().getName())));
        event.getTown().getResidents().forEach(resident -> synchroniseNextTick(resident.getUUID()));
    }

    @EventHandler
    public void onTownLeavesNation(NationRemoveTownEvent event) {
        plugin.manager().sendTownNotification(event.getTown(), TDCMessages.tr(plugin, "events.nation_left", Map.of("nation", event.getNation().getName())));
        event.getTown().getResidents().forEach(resident -> synchroniseNextTick(resident.getUUID()));
    }

    @EventHandler
    public void onTownRename(RenameTownEvent event) {
        plugin.manager().renameTown(event.getOldName(), event.getTown().getName());
    }

    @EventHandler
    public void onNationRename(RenameNationEvent event) {
        plugin.manager().renameNation(event.getOldName(), event.getNation().getName());
    }

    @EventHandler
    public void onTownDeleted(DeleteTownEvent event) {
        plugin.manager().deleteTown(event.getTownName());
    }

    @EventHandler
    public void onNationDeleted(DeleteNationEvent event) {
        plugin.manager().deleteNation(event.getNationName());
    }

    @EventHandler
    public void onMayorChanged(TownMayorChangeEvent event) {
        if (event.isCancelled()) return;
        Town town = event.getTown();
        plugin.manager().refreshTownStaff(town);
        plugin.manager().sendTownNotification(town, TDCMessages.tr(plugin, "events.new_mayor", Map.of("resident", event.getNewMayor().getName())));
    }

    /** Registers only where the installed Towny version offers a bank event. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void registerOptionalBankListener() {
        for (String className : new String[]{
                "com.palmergames.bukkit.towny.event.BankTransactionEvent",
                "com.palmergames.bukkit.towny.event.economy.BankTransactionEvent"}) {
            try {
                Class<?> rawClass = Class.forName(className, false, Town.class.getClassLoader());
                if (!Event.class.isAssignableFrom(rawClass)) continue;
                EventExecutor executor = (listener, event) -> onBankTransaction(event);
                plugin.getServer().getPluginManager().registerEvent((Class) rawClass, this, EventPriority.MONITOR, executor, plugin, true);
                plugin.getLogger().info("Listening for Towny bank transactions via " + className + ".");
                return;
            } catch (ClassNotFoundException ignored) {
                // This Towny build does not publish the event; daily finance reports remain available.
            }
        }
        plugin.getLogger().info("Towny bank transaction event unavailable; using daily financial summaries only.");
    }

    /** Registers jail and outlaw notifications without hard-linking to one Towny event version. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void registerOptionalLawListeners() {
        registerOptionalLawEvent("com.palmergames.bukkit.towny.event.resident.ResidentJailEvent", "jail");
        registerOptionalLawEvent("com.palmergames.bukkit.towny.event.resident.ResidentUnjailEvent", "unjail");
        for (String className : new String[]{
                "com.palmergames.bukkit.towny.event.town.TownAddOutlawEvent",
                "com.palmergames.bukkit.towny.event.town.TownRemoveOutlawEvent",
                "com.palmergames.bukkit.towny.event.TownAddOutlawEvent",
                "com.palmergames.bukkit.towny.event.TownRemoveOutlawEvent",
                "com.palmergames.bukkit.towny.event.TownOutlawAddEvent",
                "com.palmergames.bukkit.towny.event.TownOutlawRemoveEvent"}) {
            String action = className.toLowerCase(Locale.ROOT).contains("remove") ? "outlaw-remove" : "outlaw-add";
            outlawEventsAvailable |= registerOptionalLawEvent(className, action);
        }
    }

    /** Registers rank and tax notifications across Towny API naming variants. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void registerOptionalTownChangeListeners() {
        registerOptionalTownChangeEvent(new String[]{
                "com.palmergames.bukkit.towny.event.TownAddResidentRankEvent",
                "com.palmergames.bukkit.towny.event.ResidentRankAddEvent",
                "com.palmergames.bukkit.towny.event.town.ResidentRankAddEvent"}, "rank-add");
        registerOptionalTownChangeEvent(new String[]{
                "com.palmergames.bukkit.towny.event.TownRemoveResidentRankEvent",
                "com.palmergames.bukkit.towny.event.ResidentRankRemoveEvent",
                "com.palmergames.bukkit.towny.event.town.ResidentRankRemoveEvent"}, "rank-remove");
        registerOptionalTownChangeEvent(new String[]{
                "com.palmergames.bukkit.towny.event.TownSetTaxesEvent",
                "com.palmergames.bukkit.towny.event.town.TownSetTaxesEvent",
                "com.palmergames.bukkit.towny.event.TownSetTaxEvent"}, "taxes");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerOptionalTownChangeEvent(String[] classNames, String action) {
        for (String className : classNames) {
            try {
                Class<?> rawClass = Class.forName(className, false, Town.class.getClassLoader());
                if (!Event.class.isAssignableFrom(rawClass)) continue;
                EventExecutor executor = (listener, event) -> onTownChangeEvent(action, event);
                plugin.getServer().getPluginManager().registerEvent((Class) rawClass, this, EventPriority.MONITOR, executor, plugin, true);
                plugin.getLogger().info("Listening for Towny " + action + " events via " + className + ".");
                return;
            } catch (ClassNotFoundException ignored) {
                // This Towny build uses another event name or does not expose the event.
            }
        }
        plugin.getLogger().info("Towny " + action + " event unavailable; related notifications disabled.");
    }

    private void onTownChangeEvent(String action, Event event) {
        Town town = townOf(firstObject(event, "getTown", "getGovernment"));
        if (town == null) return;
        Resident resident = residentOf(firstObject(event, "getResident", "getPlayer", "getTarget"));
        String rank = stringValue(firstObject(event, "getRank", "getTownRank", "getRankName"), "-");
        String actor = stringValue(firstObject(event, "getPlayer", "getResident", "getActor", "getSender"), "Towny");
        if (action.startsWith("rank-") && resident != null) {
            ActorHint hint = pendingRankActions.remove(rankActionKey(town.getName(), resident.getName(), rank));
            if (hint == null) hint = pendingRankActions.remove(rankActionKey(town.getName(), resident.getName(), "-"));
            if (hint != null && hint.expiresAt() >= System.currentTimeMillis()) actor = hint.playerName();
        }
        if (actor.equals("Towny") && resident != null) actor = "Towny";
        Map<String, String> values = new LinkedHashMap<>();
        values.put("town", town.getName());
        values.put("resident", resident == null ? "-" : resident.getName());
        values.put("rank", rank);
        values.put("actor", actor);
        Object tax = firstObject(event, "getTaxes", "getTax", "getNewTaxes", "getNewTax");
        values.put("tax", stringValue(tax, String.valueOf(town.getTaxes())));
        String path = "messages.TownEvents." + action;
        if (!plugin.configuration().getBoolean(path + ".Enabled", true)) return;
        String format = plugin.configuration().getString(path + ".Format", defaultTownChangeMessage(action));
        plugin.manager().sendConfiguredTownEvent(town, format, values,
                resident == null ? null : Bukkit.getOfflinePlayer(resident.getUUID()));
    }

    private String defaultTownChangeMessage(String action) {
        return switch (action) {
            case "rank-add" -> TDCMessages.tr(plugin, "events.rank_add", Map.of("resident", "%resident%", "rank", "%rank%", "actor", "%actor%"));
            case "rank-remove" -> TDCMessages.tr(plugin, "events.rank_remove", Map.of("resident", "%resident%", "rank", "%rank%", "actor", "%actor%"));
            default -> TDCMessages.tr(plugin, "events.taxes", Map.of("tax", "%tax%", "actor", "%actor%"));
        };
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        if (value instanceof Resident resident) return resident.getName();
        if (value instanceof org.bukkit.entity.Player player) return player.getName();
        return String.valueOf(value);
    }

    private String rankActionKey(String town, String resident, String rank) {
        return (town + "|" + resident + "|" + rank).toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean registerOptionalLawEvent(String className, String action) {
        try {
            Class<?> rawClass = Class.forName(className, false, Town.class.getClassLoader());
            if (!Event.class.isAssignableFrom(rawClass)) return false;
            EventExecutor executor = (listener, event) -> onLawEvent(action, event);
            plugin.getServer().getPluginManager().registerEvent((Class) rawClass, this, EventPriority.MONITOR, executor, plugin, true);
            plugin.getLogger().info("Listening for Towny " + action + " events via " + className + ".");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private void onLawEvent(String action, Event event) {
        Resident resident = residentOf(firstObject(event, "getResident", "getOutlaw", "getTarget", "getPlayer"));
        Town town = townOf(firstObject(event, "getJailTown", "getTown", "getGovernment", "getOwner"));
        if (town == null && resident != null) town = resident.getTownOrNull();
        if (town == null || resident == null) return;
        if ((action.equals("jail") || action.equals("unjail"))
                && !plugin.manager().isTownFeatureEnabled(town, "jail")) return;
        String residentName = resident.getName();
        switch (action) {
            case "jail" -> {
                Object hoursValue = firstObject(event, "getJailHours", "getHours");
                String duration = hoursValue instanceof Number number && number.intValue() > 0
                        ? " for **" + number.intValue() + " hours**" : "";
                plugin.manager().sendTownNotification(town, TDCMessages.tr(plugin, "events.jail", Map.of("resident", residentName, "duration", duration)));
            }
            case "unjail" -> plugin.manager().sendTownNotification(town, TDCMessages.tr(plugin, "events.unjail", Map.of("resident", residentName)));
            case "outlaw-add" -> plugin.manager().sendTownNotification(town, TDCMessages.tr(plugin, "events.outlaw_add", Map.of("resident", residentName)));
            case "outlaw-remove" -> plugin.manager().sendTownNotification(town, TDCMessages.tr(plugin, "events.outlaw_remove", Map.of("resident", residentName)));
            default -> { }
        }
    }

    /** Compatibility fallback for Towny builds that do not publish outlaw add/remove events. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOutlawCommand(PlayerCommandPreprocessEvent event) {
        if (outlawEventsAvailable) return;
        String[] words = event.getMessage().trim().split("\\s+");
        if (words.length < 4 || !words[0].matches("(?i)/(?:town|t|towny:town)") || !words[1].equalsIgnoreCase("outlaw")) return;
        boolean add = words[2].equalsIgnoreCase("add");
        boolean remove = words[2].equalsIgnoreCase("remove") || words[2].equalsIgnoreCase("del");
        if (!add && !remove) return;
        Resident senderResident = TownyUniverse.getInstance().getResident(event.getPlayer().getUniqueId());
        Town town = senderResident == null ? null : senderResident.getTownOrNull();
        if (town == null) return;
        String targetName = words[3];
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Resident target = TownyUniverse.getInstance().getResident(targetName);
            if (target == null || town.hasOutlaw(target) != add) return;
            String message = add
                    ? "⚖️ **Outlaw:** " + target.getName() + " was added to the outlaws."
                    : "⚖️ **Outlaw:** " + target.getName() + " was removed from the outlaws.";
            plugin.manager().sendTownNotification(town, message);
        });
    }

    private void onBankTransaction(Event event) {
        Object account = call(event, "getAccount");
        Object transaction = call(event, "getTransaction");
        if (account == null || transaction == null) return;
        for (Town town : TownyUniverse.getInstance().getTowns()) {
            if (!town.getAccount().equals(account)) continue;
            Object amountValue = call(transaction, "getAmount");
            Object type = call(transaction, "getType");
            String rawReason = firstText(event, transaction, "getReason", "getDescription", "getMessage", "getCause");
            String actor = resolveActor(event, transaction, rawReason);
            ActorHint hint = takeRecentManualHint(town.getName());
            if (actor.equals("Sistema") && hint != null) actor = hint.playerName();
            String reason = normaliseReason(typeName(type), rawReason, hint);
            double amountNumber = amountValue instanceof Number number ? number.doubleValue() : 0D;
            String typeName = typeName(type);
            plugin.manager().sendTownBankEmbed(town, typeName, amountNumber, actor, reason);
        }
    }

    private Object firstObject(Object source, String... methodNames) {
        for (String methodName : methodNames) {
            Object value = call(source, methodName);
            if (value != null) return value;
        }
        return null;
    }

    private Resident residentOf(Object value) {
        if (value instanceof Resident resident) return resident;
        if (value instanceof OfflinePlayer player) return TownyUniverse.getInstance().getResident(player.getUniqueId());
        if (value instanceof UUID uuid) return TownyUniverse.getInstance().getResident(uuid);
        if (value instanceof String name) return TownyUniverse.getInstance().getResident(name);
        Object uuid = firstObject(value, "getUniqueId", "getUUID", "getPlayerUUID");
        if (uuid instanceof UUID id) return TownyUniverse.getInstance().getResident(id);
        Object name = call(value, "getName");
        return name instanceof String text ? TownyUniverse.getInstance().getResident(text) : null;
    }

    private Town townOf(Object value) {
        if (value instanceof Town town) return town;
        if (value instanceof Resident resident) return resident.getTownOrNull();
        if (value instanceof String name) return TownyUniverse.getInstance().getTown(name);
        Object town = call(value, "getTown");
        if (town instanceof Town resolvedTown) return resolvedTown;
        Object name = call(value, "getName");
        return name instanceof String text ? TownyUniverse.getInstance().getTown(text) : null;
    }

    private ActorHint takeRecentManualHint(String townName) {
        ActorHint hint = pendingManualBankActions.remove(townName.toLowerCase(Locale.ROOT));
        return hint != null && hint.expiresAt() >= System.currentTimeMillis() ? hint : null;
    }

    private String normaliseReason(String typeName, String rawReason, ActorHint hint) {
        String raw = rawReason == null ? "" : rawReason.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("tax") || lower.contains("tassa")) return "Taxes";
        if (!raw.isBlank() && !raw.equalsIgnoreCase("Not given")) return raw;
        if (hint != null) return hint.reason();
        if (System.currentTimeMillis() <= taxCollectionWindowEnds && isDeposit(typeName)) return "Taxes";
        if (isDeposit(typeName)) return "Deposito manuale";
        if (typeName.toLowerCase(Locale.ROOT).contains("withdraw") || typeName.toLowerCase(Locale.ROOT).contains("preliev")) {
            return "Manual withdrawal";
        }
        return "Operazione Towny";
    }

    private boolean isDeposit(String typeName) {
        String normalised = typeName.toLowerCase(Locale.ROOT);
        return normalised.contains("deposit") || normalised.equals("add") || normalised.contains("accredito");
    }

    private String typeName(Object type) {
        String name = type == null ? null : textOf(call(type, "getName"));
        return name == null || name.isBlank() ? "Operazione" : name;
    }

    /** Supports both legacy and current Towny transaction models without losing the actor. */
    private String resolveActor(Object event, Object transaction, String reason) {
        String[] actorMethods = {"getPlayer", "getResident", "getActor", "getInitiator", "getExecutor", "getSender", "getSource", "getFrom", "getUser", "getOwner", "getPlayerUUID", "getPlayerId", "getPlayerUniqueId", "getResidentUUID"};
        for (Object source : new Object[]{event, transaction}) {
            for (String method : actorMethods) {
                String name = textOf(call(source, method));
                if (name != null) return name;
            }
            for (String method : new String[]{"getPlayerName", "getResidentName", "getActorName", "getInitiatorName", "getSenderName"}) {
                String name = textOf(call(source, method));
                if (name != null) return name;
            }
            for (String field : new String[]{"player", "resident", "actor", "initiator", "executor", "sender", "source", "from", "user", "owner", "playerId", "playerUUID"}) {
                String name = textOf(field(source, field));
                if (name != null) return name;
            }
        }
        if (reason != null) {
            Matcher matcher = Pattern.compile("(?i)(?:from|by|da)\\s+([\\p{L}\\w.-]+)").matcher(reason);
            if (matcher.find()) return matcher.group(1);
        }
        return "Sistema";
    }

    private String firstText(Object first, Object second, String... methodNames) {
        for (String method : methodNames) {
            String value = textOf(call(first, method));
            if (value != null) return value;
            value = textOf(call(second, method));
            if (value != null) return value;
        }
        return "Not given";
    }

    private String textOf(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return text.isBlank() ? null : text;
        if (value instanceof Resident resident) return resident.getName();
        if (value instanceof OfflinePlayer player) return player.getName();
        if (value instanceof UUID uuid) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            return player.getName() == null ? null : player.getName();
        }
        Object name = call(value, "getName");
        if (name instanceof String text && !text.isBlank()) return text;
        Object uuid = call(value, "getUUID");
        if (uuid instanceof UUID id) return textOf(id);
        return null;
    }

    private Object call(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private Object field(Object target, String fieldName) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return null;
            }
        }
        return null;
    }

    private void publishDailyReports() {
        for (Town town : TownyUniverse.getInstance().getTowns()) {
            plugin.manager().refreshTownStaff(town);
            plugin.manager().sendTownDailySummary(town);
        }
    }

    private void publishGlobalTownFallReports(Map<String, TownFallSnapshot> snapshots,
                                              List<String> bankrupted, List<String> fallen) {
        Map<String, String> causes = new LinkedHashMap<>();
        for (String townName : bankrupted) causes.put(townName.toLowerCase(Locale.ROOT), "bankrupt");
        // A town which reaches the debt cap can occur in both lists: final deletion wins.
        for (String townName : fallen) causes.put(townName.toLowerCase(Locale.ROOT), "fallen");
        for (Map.Entry<String, TownFallSnapshot> entry : snapshots.entrySet()) {
            Town current = TownyUniverse.getInstance().getTown(entry.getValue().town());
            if (current != null && !entry.getValue().ruined() && current.isRuined()) {
                causes.putIfAbsent(entry.getKey(), "ruined");
            }
        }
        for (Map.Entry<String, String> entry : causes.entrySet()) {
            TownFallSnapshot before = snapshots.get(entry.getKey());
            Town current = before == null ? null : TownyUniverse.getInstance().getTown(before.town());
            TownFallSnapshot after = current == null ? null : plugin.manager().captureTownFallSnapshot(current);
            if (before == null) before = after;
            if (before != null) plugin.manager().sendGlobalTownFallEmbed(before, after, entry.getValue());
        }
        preNewDayTownSnapshots.clear();
    }

    private void synchroniseNextTick(java.util.UUID playerId) {
        // without roles the manager batches everything that changed this tick and applies it on the next one
        if (!plugin.manager().rolesMode()) {
            plugin.manager().synchronisePlayer(playerId);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.manager().synchronisePlayer(playerId));
    }

    private record ActorHint(String playerName, String reason, long expiresAt) {
    }
}
