package com.TownyDiscordChat.TownyDiscordChat;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Optional, reflection-only Dynmap bridge. It downloads Dynmap's own rendered tiles, never world data. */
public final class DynmapTownMapRenderer {
    private DynmapTownMapRenderer() {
    }

    /** Checks the stable Dynmap API only at execution time, avoiding a hard plugin dependency. */
    public static String validateDynmapWorld(String worldName) {
        Plugin dynmap = Bukkit.getPluginManager().getPlugin("dynmap");
        if (dynmap == null || !dynmap.isEnabled()) {
            return "Dynmap isn't installed or isn't running.";
        }
        try {
            Class<?> apiClass = Class.forName("org.dynmap.DynmapAPI", false, dynmap.getClass().getClassLoader());
            if (!apiClass.isInstance(dynmap)) {
                return "this Dynmap install doesn't expose its public API.";
            }
            Method getWorld = apiClass.getMethod("getWorld", String.class);
            if (getWorld.invoke(dynmap, worldName) == null) {
                return "Dynmap has no map for the world `" + worldName + "`.";
            }
            return null;
        } catch (ClassNotFoundException ignored) {
            return "this Dynmap install doesn't expose its public API.";
        } catch (ReflectiveOperationException exception) {
            return "couldn't query the Dynmap API: " + exception.getClass().getSimpleName() + ".";
        }
    }

    public static Result render(Main plugin, Request request) {
        FileConfiguration config = plugin.configuration();
        int tileSize = Math.max(32, config.getInt("dynmap.TileSize", 128));
        int tilesPerSide = clamp(config.getInt("dynmap.TilesPerSide", 3), 1, 7);
        int timeout = clamp(config.getInt("dynmap.RequestTimeoutSeconds", 5), 1, 30);
        int zoom = Math.max(0, config.getInt("dynmap.Zoom", 0));
        String template = config.getString("dynmap.TileUrlTemplate", "");
        String webUrl = stripTrailingSlash(config.getString("dynmap.WebUrl", ""));
        String prefix = config.getString("dynmap.MapPrefix", "flat");
        if (template.isBlank() || webUrl.isBlank() || prefix.isBlank()) {
            return Result.error("Config Dynmap incompleto: imposta WebUrl, MapPrefix e TileUrlTemplate.");
        }

        double mapX = request.x() * config.getDouble("dynmap.MapX.WorldXMultiplier", 1D)
                + request.z() * config.getDouble("dynmap.MapX.WorldZMultiplier", 0D)
                + config.getDouble("dynmap.MapX.Offset", 0D);
        double mapY = request.x() * config.getDouble("dynmap.MapY.WorldXMultiplier", 0D)
                + request.z() * config.getDouble("dynmap.MapY.WorldZMultiplier", 1D)
                + config.getDouble("dynmap.MapY.Offset", 0D);
        int centerX = (int) Math.floor(mapX / tileSize);
        int centerY = (int) Math.floor(mapY / tileSize);
        int side = tileSize * tilesPerSide;
        BufferedImage output = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        graphics.setColor(new Color(29, 34, 41));
        graphics.fillRect(0, 0, side, side);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout)).build();
        int loaded = 0;
        int half = tilesPerSide / 2;
        for (int row = 0; row < tilesPerSide; row++) {
            for (int column = 0; column < tilesPerSide; column++) {
                int tileX = centerX + column - half;
                int tileY = centerY + row - half;
                BufferedImage tile = downloadTile(client, tileUrl(template, webUrl, request.world(), prefix, zoom, tileX, tileY), timeout);
                int drawX = column * tileSize;
                int drawY = row * tileSize;
                if (tile != null) {
                    graphics.drawImage(tile, drawX, drawY, tileSize, tileSize, null);
                    loaded++;
                } else {
                    graphics.setColor(new Color(45, 52, 62));
                    graphics.fillRect(drawX, drawY, tileSize, tileSize);
                    graphics.setColor(new Color(65, 73, 85));
                    graphics.drawRect(drawX, drawY, tileSize - 1, tileSize - 1);
                }
            }
        }
        if (loaded == 0) {
            graphics.dispose();
            return Result.error("Dynmap returned no tiles. Check WebUrl, MapPrefix and MapX/MapY in the config.");
        }
        drawOverlay(graphics, side, request.town());
        graphics.dispose();
        try {
            Path directory = plugin.getDataFolder().toPath().resolve("generated-maps");
            Files.createDirectories(directory);
            Path image = Files.createTempFile(directory, "town-" + safeFilePart(request.town()) + "-", ".png");
            ImageIO.write(output, "png", image.toFile());
            return Result.image(image, request.town(), loaded);
        } catch (IOException exception) {
            return Result.error("Couldn't save the Dynmap image: " + exception.getClass().getSimpleName() + ".");
        }
    }

    private static BufferedImage downloadTile(HttpClient client, String url, int timeoutSeconds) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds)).GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
            return ImageIO.read(new java.io.ByteArrayInputStream(response.body()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String tileUrl(String template, String webUrl, String world, String prefix, int zoom, int mapX, int mapY) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("%web_url%", webUrl);
        values.put("%world%", world);
        values.put("%map_prefix%", prefix);
        values.put("%chunk_x%", String.valueOf(Math.floorDiv(mapX, 32)));
        values.put("%chunk_y%", String.valueOf(Math.floorDiv(-mapY, 32)));
        values.put("%zoom_prefix%", zoom == 0 ? "" : "z".repeat(zoom) + "_");
        values.put("%map_x%", String.valueOf(mapX));
        values.put("%map_y%", String.valueOf(mapY));
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) result = result.replace(entry.getKey(), entry.getValue());
        return result;
    }

    private static void drawOverlay(Graphics2D graphics, int side, String town) {
        graphics.setComposite(AlphaComposite.SrcOver.derive(.78f));
        graphics.setColor(new Color(14, 18, 24));
        graphics.fillRoundRect(12, 12, Math.min(side - 24, 360), 40, 10, 10);
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        graphics.drawString("Dynmap · " + town, 24, 38);
        int center = side / 2;
        graphics.setColor(new Color(231, 76, 60));
        graphics.setStroke(new BasicStroke(3F));
        graphics.drawLine(center - 10, center, center + 10, center);
        graphics.drawLine(center, center - 10, center, center + 10);
        graphics.drawOval(center - 6, center - 6, 12, 12);
    }

    private static int clamp(int number, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, number));
    }

    private static String stripTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private static String safeFilePart(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public record Request(String town, String world, double x, double z) {
    }

    public record Result(Path image, String town, int loadedTiles, String error) {
        public static Result image(Path image, String town, int loadedTiles) {
            return new Result(image, town, loadedTiles, null);
        }

        public static Result error(String error) {
            return new Result(null, null, 0, error);
        }

        public boolean successful() {
            return image != null;
        }
    }
}
