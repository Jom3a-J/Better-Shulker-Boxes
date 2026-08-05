package com.bettershulker.client;

import com.bettershulker.BetterShulkerMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Checks Modrinth for a newer release after the client joins a world or server. */
public final class UpdateChecker {
    private static final String MODRINTH_VERSION_API =
            "https://api.modrinth.com/v2/project/UUntxQ5e/version";
    private static final URI MODRINTH_PROJECT_URI =
            URI.create("https://modrinth.com/mod/better-shulker-boxes");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Invalidates results that finish after the player has left the current connection. */
    private static final AtomicLong connectionSequence = new AtomicLong();

    private UpdateChecker() {
    }

    /** Starts one non-blocking update check for the current client connection. */
    public static void checkForUpdates(Minecraft client, String currentVersion) {
        if (client == null || currentVersion == null || currentVersion.isBlank()) {
            return;
        }

        long connectionId = connectionSequence.incrementAndGet();
        String minecraftVersion = SharedConstants.getCurrentVersion().name();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MODRINTH_VERSION_API))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "Better-Shulker-Boxes/" + currentVersion)
                .GET()
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new UpdateCheckException("Modrinth returned HTTP " + response.statusCode());
                    }
                    return findLatestRelease(response.body(), minecraftVersion);
                })
                .thenAccept(latestVersion -> client.execute(() -> {
                    if (connectionId != connectionSequence.get() || client.player == null) {
                        return;
                    }
                    if (latestVersion != null && compareVersions(latestVersion, currentVersion) > 0) {
                        showUpdateMessage(client, currentVersion, latestVersion);
                    }
                }))
                .exceptionally(error -> {
                    BetterShulkerMod.LOGGER.debug(
                            "[BetterShulker] Update check failed: {}",
                            error.getMessage()
                    );
                    return null;
                });
    }

    /** Invalidates an in-flight result when the client leaves its world or server. */
    public static void onDisconnect() {
        connectionSequence.incrementAndGet();
    }

    private static String findLatestRelease(String responseBody, String minecraftVersion) {
        try {
            JsonElement root = JsonParser.parseString(responseBody);
            if (!root.isJsonArray()) {
                throw new UpdateCheckException("Modrinth returned a non-array version list");
            }

            String latestVersion = null;
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject version = element.getAsJsonObject();
                if (!isRelease(version) || !supportsMinecraftVersion(version, minecraftVersion)) {
                    continue;
                }

                String versionNumber = getString(version, "version_number");
                if (!isVersionValid(versionNumber)) {
                    continue;
                }

                if (latestVersion == null || compareVersions(versionNumber, latestVersion) > 0) {
                    latestVersion = versionNumber;
                }
            }
            return latestVersion;
        } catch (RuntimeException exception) {
            throw new UpdateCheckException("Could not parse Modrinth version list", exception);
        }
    }

    private static boolean isRelease(JsonObject version) {
        String type = getString(version, "version_type");
        String status = getString(version, "status");
        return "release".equalsIgnoreCase(type)
                && (status.isEmpty() || "listed".equalsIgnoreCase(status));
    }

    private static boolean supportsMinecraftVersion(JsonObject version, String minecraftVersion) {
        JsonElement gameVersions = version.get("game_versions");
        if (gameVersions == null || !gameVersions.isJsonArray()) {
            return true;
        }

        for (JsonElement gameVersion : gameVersions.getAsJsonArray()) {
            if (minecraftVersion.equals(gameVersion.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String getString(JsonObject object, String property) {
        JsonElement value = object.get(property);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    private static boolean isVersionValid(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }

        String normalized = normalizeVersion(version);
        String numericPart = normalized.split("[-+]", 2)[0];
        if (numericPart.isEmpty()) {
            return false;
        }

        for (String part : numericPart.split("\\.")) {
            if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) {
                return false;
            }
        }
        return true;
    }

    /** Compares common semantic version strings, including prerelease suffixes. */
    static int compareVersions(String left, String right) {
        ParsedVersion leftVersion = parseVersion(left);
        ParsedVersion rightVersion = parseVersion(right);
        if (leftVersion == null || rightVersion == null) {
            return 0;
        }

        int componentCount = Math.max(leftVersion.numericComponents.size(), rightVersion.numericComponents.size());
        for (int i = 0; i < componentCount; i++) {
            int leftComponent = i < leftVersion.numericComponents.size()
                    ? leftVersion.numericComponents.get(i)
                    : 0;
            int rightComponent = i < rightVersion.numericComponents.size()
                    ? rightVersion.numericComponents.get(i)
                    : 0;
            if (leftComponent != rightComponent) {
                return Integer.compare(leftComponent, rightComponent);
            }
        }

        if (leftVersion.prerelease.isEmpty() != rightVersion.prerelease.isEmpty()) {
            return leftVersion.prerelease.isEmpty() ? 1 : -1;
        }
        return leftVersion.prerelease.compareToIgnoreCase(rightVersion.prerelease);
    }

    private static ParsedVersion parseVersion(String version) {
        if (!isVersionValid(version)) {
            return null;
        }

        String normalized = normalizeVersion(version);
        String[] mainAndPrerelease = normalized.split("[-+]", 2);
        List<Integer> numericComponents = new ArrayList<>();
        for (String component : mainAndPrerelease[0].split("\\.")) {
            try {
                numericComponents.add(Integer.parseInt(component));
            } catch (NumberFormatException exception) {
                return null;
            }
        }

        String prerelease = mainAndPrerelease.length > 1 && normalized.contains("-")
                ? normalized.substring(normalized.indexOf('-') + 1)
                : "";
        return new ParsedVersion(numericComponents, prerelease);
    }

    private static String normalizeVersion(String version) {
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static void showUpdateMessage(Minecraft client, String currentVersion, String latestVersion) {
        Component message = Component.literal("[Better Shulker Boxes] ")
                .withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("A new version is available: ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(latestVersion)
                        .withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" (you have " + currentVersion + ") ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("[Download]")
                        .withStyle(style -> style
                                .withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.OpenUrl(MODRINTH_PROJECT_URI))));
        client.player.sendSystemMessage(message);
    }

    private record ParsedVersion(List<Integer> numericComponents, String prerelease) {
    }

    private static final class UpdateCheckException extends RuntimeException {
        private UpdateCheckException(String message) {
            super(message);
        }

        private UpdateCheckException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
