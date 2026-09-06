# TownyDiscordChat 3.2.4

TownyDiscordChat is a Paper/Purpur bridge between Towny Advanced and DiscordSRV. It creates private town and nation resources, provides separate town and nation two-way chat bridges, keeps linked accounts synchronized, and publishes configurable Towny activity to Discord.

## Compatibility

- Paper/Purpur 1.21+ and 26.*
- Java 21+ for 1.21.x; Java 25 is required by Paper 26.*
- Public Bukkit/Paper APIs only; no NMS or obfuscated server classes

## Requirements

Required plugins:

- [Towny Advanced](https://github.com/TownyAdvanced/Towny)
- [DiscordSRV](https://github.com/DiscordSRV/DiscordSRV), configured with a Main Guild

Optional soft-dependencies:

- [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI)
- [Dynmap](https://github.com/webbukkit/dynmap)
- [InteractiveChat](https://github.com/LOOHP/InteractiveChat)
- [InteractiveChat DiscordSRV Addon](https://modrinth.com/plugin/interactivechat-discordsrv-addon)
- TownyChat

## Features

- Automatically creates and synchronizes `town-<name>` and `nation-<name>` roles, text channels and voice channels.
- Town-only Minecraft ↔ Discord chat bridge with linked-account and wrong-town protection.
- Nation Minecraft ↔ Discord chat bridge with nation-membership validation and configurable nation/nc channel detection.
- DiscordSRV global chat remains available normally; TownyDiscordChat only intercepts messages explicitly detected as Towny town or nation chat.
- Sends town notifications in the main town channel; no separate staff channel is required.
- Configurable bank embeds with transaction type, amount, balance, actor, reason and PlaceholderAPI values.
- Town events for residents joining/leaving, mayor changes, rank changes, jail, outlaw and tax changes.
- NewDay summaries with Taxes, Residents and Outposts buttons.
- Resident lists with ranks and last access, plus paginated outpost names, PlotGroup names and coordinates.
- Global NewDay embeds for bankrupt, ruined or fallen towns, with configurable story variants and NPC residents excluded.
- Dynmap overhead images through `/town map`.
- InteractiveChat item previews through the DiscordSRV addon, plus a fallback item embed for supported components.
- Built-in locales: English, Italian, French, Spanish and Polish.

## Languages

English is the default language. Set the following in `plugins/TownyDiscordChat/config.yml`:

```yaml
language: en # en, it, fr, es or pl
```

On first start, the plugin copies the five locale files to `plugins/TownyDiscordChat/locales/`. Missing keys fall back to English. These files can be edited without rebuilding the plugin.

## Installation

1. Install Towny Advanced and DiscordSRV and configure DiscordSRV's Main Guild.
2. Give the Discord bot Manage Roles, Manage Channels, Manage Channel Permissions, View Channels, Send Messages, Embed Links and Read Message History.
3. Put the bot role above the roles created by TownyDiscordChat.
4. Enable Discord's Message Content Intent.
5. Place the jar in `plugins/`, start the server, edit `config.yml`, then run `/tdc sync` as console or an administrator.

## Commands

### Minecraft

- `/tdc check role` — synchronize your own Towny roles.
- `/tdc check role alllinked` — synchronize all linked accounts.
- `/tdc check role createalltownsandnations` — create or repair all Towny resources.
- `/tdc check textchannel alltownsandnations` — verify town and nation text channels.
- `/tdc check voicechannel alltownsandnations` — verify voice channels.
- `/tdc sync` — synchronize all resources and linked accounts.
- `/tdc channels delete <town|all>` — delete town channels while keeping roles (admin or console).
- `/tdc channels restore <town|all>` — re-enable and recreate intentionally deleted channels.
- `/t discord <newday|chat|jail> <enable|disable>` — configure a town feature as mayor or assistant.

### Discord

- `/town info` — private summary of your linked town.
- `/town sync` — synchronize your Discord roles.
- `/town map [town]` — show a Dynmap overhead image; other towns require Discord Administrator or a configured admin role.
- `/town notice message:<text>` — send a notice to the town channel as mayor or assistant.
- `/town resync` — synchronize all resources; Discord Administrator only.

## Configuration

All bridge formats, embed titles, descriptions, fields, buttons, event messages, PlaceholderAPI placeholders, Dynmap settings and feature toggles are configurable in `config.yml`. Native placeholders include `%town%`, `%mayor%`, `%message%`, `%actor%`, `%resident%`, `%rank%`, `%tax%`, `%balance%` and `%reason%`.

TownyChat detection can be configured with `townychat.TownChannelNames` and `townychat.NationChannelNames`. Nation messages use `bridge.NationMinecraftFormat` and `bridge.NationDiscordFormat`.

## Permissions

`TownyDiscordChat.Admin`, `TownyDiscordChat.Player`, `TownyDiscordChat.Sync`, `TownyDiscordChat.Check.Role`, `TownyDiscordChat.Check.Role.AllLinked`, `TownyDiscordChat.Check.Role.CreateAllTownsAndNations`, `TownyDiscordChat.Check.TextChannel.AllTownsAndNations`, `TownyDiscordChat.Check.VoiceChannel.AllTownsAndNations`.

## Build

```bash
mvn clean package
```

The artifact is generated as `target/TownyDiscordChat-3.2.4-1.21+-26+.jar`.

## Inspiration and credits

Inspired by [TownyDiscordChat on SpigotMC](https://www.spigotmc.org/resources/townydiscordchat.91026/).

Authors and contributors: thejames10, Hugo5000 and Smokytek.

## Support the project

If TownyDiscordChat is useful to you, please [star the project on GitHub](https://github.com/smokytek/TownyDiscordChat). Stars help the project reach more server owners and motivate future development.
