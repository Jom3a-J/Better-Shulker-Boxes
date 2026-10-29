package com.bettershulker.gametest;

import com.bettershulker.BetterShulkerConfig;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static com.bettershulker.gametest.GameTestSupport.assertTrue;

/**
 * The config file lives in the folder the loader names, and is saved without a half-written state.
 *
 * <p>It used to be written to {@code config/} under the working directory, which is only the
 * game's folder when the launcher happens to start the game there. The save also truncated the
 * file before writing it, so a crash in between left it empty.</p>
 *
 * <p>Runs against a fresh temporary folder and puts the real path and value back afterwards.</p>
 */
public class ConfigFileGameTest implements FabricClientGameTest {

    private static final float SAVED_VOLUME = 0.75f;

    @Override
    public void runTest(ClientGameTestContext context) {
        String failure = context.computeOnClient(client -> {
            Path originalPath = BetterShulkerConfig.getConfigPath();
            float originalVolume = BetterShulkerConfig.getSoundVolume();
            try {
                Path dir = Files.createTempDirectory("bettershulker-config-test");
                BetterShulkerConfig.setConfigDir(dir);
                return saveAndReload(dir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                BetterShulkerConfig.setConfigDir(originalPath.toAbsolutePath().getParent());
                BetterShulkerConfig.setSoundVolume(originalVolume);
            }
        });
        assertTrue(failure == null, failure);
    }

    /** Returns what went wrong, or null when the file landed in {@code dir} and read back. */
    private static String saveAndReload(Path dir) throws IOException {
        BetterShulkerConfig.setSoundVolume(SAVED_VOLUME);
        BetterShulkerConfig.save();

        List<String> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.map(path -> path.getFileName().toString()).sorted().toList();
        }
        if (!files.equals(List.of("bettershulker-plus.properties"))) {
            return "the save should leave exactly the config file in the loader's folder, found " + files;
        }

        BetterShulkerConfig.setSoundVolume(0.1f);
        BetterShulkerConfig.load();
        float loaded = BetterShulkerConfig.getSoundVolume();
        if (loaded != SAVED_VOLUME) {
            return "loading should read the saved volume " + SAVED_VOLUME + " back, got " + loaded;
        }
        return null;
    }
}
