package com.bettershulker.client.render;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses the Shulker Box definitions a resource pack ships for OptiFine Custom GUI
 * ({@code optifine/gui/container/**.properties}) and OptiGUI ({@code optigui:gui/**.ini}).
 *
 * <p>Both formats are read the way their own mods read them rather than by guessing where a pack
 * keeps its files: real packs put their per-dye panels under {@code shulker_box/},
 * {@code shulkers/}, {@code shulker_boxes/}, a folder per dye, or nowhere in particular, and name
 * the textures however they like. What a definition applies to is decided by its
 * {@code colors} key or its section header, never by its path.</p>
 *
 * <p>Parsing is kept apart from resource lookup: a rule only lists the texture paths it could
 * mean, and the caller keeps the first one the resource manager actually has. That is the same
 * order both mods try them in, and it lets the parsing be checked without a pack installed.</p>
 */
public final class ShulkerGuiRules {

    /** Block id of the undyed box, which is also what an OptiGUI section names it by. */
    public static final String UNDYED = "shulker_box";

    private static final String SHULKER_TEXTURE = "textures/gui/container/shulker_box.png";

    private ShulkerGuiRules() {
    }

    /**
     * One definition, before its texture has been looked up.
     *
     * @param targets  block ids without a namespace, {@link #UNDYED} or {@code <dye>_shulker_box}
     * @param textures paths the definition's texture value could mean, most likely first
     * @param priority OptiGUI's {@code load.priority}; higher is tried first, OptiFine has none
     */
    public record Rule(Identifier source, Set<String> targets, NameMatcher name,
                       List<Identifier> textures, int priority, boolean ini) {

        public boolean appliesTo(String boxId, String title) {
            return this.targets.contains(boxId) && this.name.matches(title);
        }
    }

    /** Block id a box of this dye is matched by; {@code null} is the undyed box. */
    public static String boxId(DyeColor color) {
        return color == null ? UNDYED : color.getName() + "_shulker_box";
    }

    // =========================================================================
    //  OptiGUI INI
    // =========================================================================

    /**
     * Reads every Shulker section of an OptiGUI INI file, in file order.
     *
     * <p>A header may name several containers ({@code [white_shulker_box black_shulker_box]}),
     * with or without the {@code minecraft:} namespace, and a token starting with {@code #} only
     * tells duplicate headers apart. Sections for other containers, without a replacement, with
     * an unreadable {@code load.priority}, or replacing some texture other than the Shulker panel
     * are dropped, as OptiGUI drops them. Selectors that need a placed block - biomes, heights,
     * comparator output, the hand used - cannot apply to an item in an inventory and are
     * ignored.</p>
     */
    public static List<Rule> parseIni(Identifier file, BufferedReader reader) throws IOException {
        List<Rule> rules = new ArrayList<>();
        Set<String> targets = null;
        Map<String, String> keys = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#") || value.startsWith(";")) continue;

            if (value.startsWith("[") && value.endsWith("]")) {
                addIniRule(rules, file, targets, keys);
                targets = shulkerTargets(value.substring(1, value.length() - 1));
                keys = new LinkedHashMap<>();
                continue;
            }
            int separator = value.indexOf('=');
            if (targets == null || separator <= 0) continue;
            keys.put(value.substring(0, separator).trim(), value.substring(separator + 1).trim());
        }
        addIniRule(rules, file, targets, keys);
        return rules;
    }

    private static Set<String> shulkerTargets(String header) {
        Set<String> targets = new LinkedHashSet<>();
        for (String token : header.trim().split("[ \t]+")) {
            if (token.isEmpty() || token.startsWith("#")) continue;
            String id = token.toLowerCase(Locale.ROOT);
            if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
            if (isShulkerId(id)) targets.add(id);
        }
        return targets;
    }

    private static void addIniRule(List<Rule> rules, Identifier file, Set<String> targets,
                                   Map<String, String> keys) {
        if (targets == null || targets.isEmpty()) return;
        String replacement = keys.get("replacement");
        if (replacement == null || replacement.isBlank()) return;

        String original = keys.get("interaction.texture");
        if (original != null && !SHULKER_TEXTURE.equals(stripMinecraftNamespace(original))) return;

        int priority = 0;
        String priorityValue = keys.get("load.priority");
        if (priorityValue != null) {
            try {
                priority = Integer.parseInt(priorityValue.trim());
            } catch (NumberFormatException e) {
                return;
            }
        }

        // Every selector in a section must match, so the name keys are combined, not chosen from.
        List<NameMatcher> names = new ArrayList<>();
        addIfPresent(names, keys.get("name"), v -> NameMatcher.exact(v));
        addIfPresent(names, keys.get("name.wildcard"), v -> NameMatcher.wildcard(v, false));
        addIfPresent(names, keys.get("name.wildcard.ignore_case"), v -> NameMatcher.wildcard(v, true));
        addIfPresent(names, keys.get("name.regex"), v -> NameMatcher.regex(v, false));
        addIfPresent(names, keys.get("name.regex.ignore_case"), v -> NameMatcher.regex(v, true));

        List<Identifier> textures = texturePaths(file, replacement, false);
        if (textures.isEmpty()) return;
        rules.add(new Rule(file, targets, NameMatcher.all(names), textures, priority, true));
    }

    private static void addIfPresent(List<NameMatcher> names, String value,
                                     java.util.function.Function<String, NameMatcher> factory) {
        // OptiGUI asks for doubled backslashes in INI values, as Java string literals would.
        if (value != null) names.add(factory.apply(value.replace("\\\\", "\\")));
    }

    private static String stripMinecraftNamespace(String path) {
        String trimmed = path.trim().replace('\\', '/');
        return trimmed.startsWith("minecraft:") ? trimmed.substring("minecraft:".length()) : trimmed;
    }

    // =========================================================================
    //  OptiFine properties
    // =========================================================================

    /**
     * Reads an OptiFine Custom GUI definition, or returns null when it is not a usable Shulker one.
     *
     * <p>The two mods disagree on a file without {@code colors}: OptiFine applies it to every box,
     * OptiGUI only to the undyed one. {@code optiGuiRules} picks which, so the tooltip shows what
     * the box's own screen does. A colour token that is not a dye is dropped rather than voiding
     * the file, which is OptiGUI's reading and the kinder one.</p>
     */
    public static Rule parseProperties(Identifier file, Properties properties, boolean optiGuiRules) {
        if (!UNDYED.equalsIgnoreCase(properties.getProperty("container", "").trim())) return null;
        if (optiGuiRules && "true".equalsIgnoreCase(properties.getProperty("optigui.ignore", "").trim())) {
            return null;
        }

        Set<String> targets = new LinkedHashSet<>();
        String colors = properties.getProperty("colors", "").trim();
        if (colors.isEmpty()) {
            targets.add(UNDYED);
            if (!optiGuiRules) {
                for (DyeColor color : DyeColor.values()) targets.add(boxId(color));
            }
        } else {
            for (String token : colors.split("[\\s,]+")) {
                DyeColor color = DyeColor.byName(token.toLowerCase(Locale.ROOT), null);
                if (color != null) targets.add(boxId(color));
            }
        }
        if (targets.isEmpty()) return null;

        String texture = properties.getProperty("texture");
        if (texture == null || texture.isBlank()) texture = properties.getProperty("texture.container/shulker_box");
        if (texture == null || texture.isBlank()) return null;
        List<Identifier> textures = texturePaths(file, texture, true);
        if (textures.isEmpty()) return null;

        // An empty "name=" is a rule, not a missing one: Recolourful Containers blanks the
        // container.shulkerBox title and uses it to give unnamed boxes their own panel.
        String name = properties.getProperty("name");
        NameMatcher matcher = name == null ? NameMatcher.ANY : NameMatcher.optiFine(name.trim());
        return new Rule(file, targets, matcher, textures, 0, false);
    }

    // =========================================================================
    //  Texture paths
    // =========================================================================

    /**
     * Every texture a definition's value could mean, in the order to try them.
     *
     * <p>A plain or {@code ./} path is relative to the definition's own folder, which is the only
     * reading OptiFine's code gives and what packs ship. OptiGUI also accepts {@code ../},
     * {@code ns:path} and, outside INI files, {@code ~/} for {@code optifine/}. A path from the
     * namespace root follows, for the packs that wrote one without a leading slash. Each is tried
     * as written and then with {@code .png} added.</p>
     */
    static List<Identifier> texturePaths(Identifier file, String value, boolean allowTilde) {
        String raw = value.trim().replace('\\', '/');
        List<String[]> candidates = new ArrayList<>();
        if (raw.isEmpty()) return List.of();

        String namespace = file.getNamespace();
        int colon = raw.indexOf(':');
        if (colon > 0 && raw.indexOf(':', colon + 1) < 0) {
            candidates.add(new String[]{raw.substring(0, colon), raw.substring(colon + 1)});
        } else if (colon >= 0) {
            return List.of();
        } else if (raw.startsWith("assets/")) {
            String rest = raw.substring("assets/".length());
            int slash = rest.indexOf('/');
            if (slash <= 0) return List.of();
            candidates.add(new String[]{rest.substring(0, slash), rest.substring(slash + 1)});
        } else if (raw.startsWith("~/")) {
            if (!allowTilde) return List.of();
            candidates.add(new String[]{namespace, "optifine/" + raw.substring(2)});
        } else if (raw.startsWith("/")) {
            candidates.add(new String[]{namespace, raw.substring(1)});
        } else {
            String folder = file.getPath();
            int lastSlash = folder.lastIndexOf('/');
            folder = lastSlash >= 0 ? folder.substring(0, lastSlash + 1) : "";
            candidates.add(new String[]{namespace, folder + raw});
            candidates.add(new String[]{namespace, raw});
            if (raw.startsWith("gui/") || raw.startsWith("container/")) {
                candidates.add(new String[]{namespace, "optifine/" + raw});
                candidates.add(new String[]{namespace, "textures/" + raw});
            }
        }

        Set<Identifier> paths = new LinkedHashSet<>();
        for (String[] candidate : candidates) {
            String path = normalise(candidate[1]);
            if (path == null) continue;
            addIdentifier(paths, candidate[0], path);
            if (!path.endsWith(".png")) addIdentifier(paths, candidate[0], path + ".png");
        }
        return List.copyOf(paths);
    }

    /** Resolves {@code .} and {@code ..}; null when the path climbs out of its namespace. */
    private static String normalise(String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) return null;
                parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    private static void addIdentifier(Set<Identifier> paths, String namespace, String path) {
        try {
            paths.add(Identifier.fromNamespaceAndPath(namespace, path));
        } catch (RuntimeException ignored) {
            // Identifiers are lower case only; a pack with capitals in a path cannot load it anyway.
        }
    }

    private static boolean isShulkerId(String id) {
        if (UNDYED.equals(id)) return true;
        if (!id.endsWith("_" + UNDYED)) return false;
        return DyeColor.byName(id.substring(0, id.length() - UNDYED.length() - 1), null) != null;
    }

    // =========================================================================
    //  Name rules
    // =========================================================================

    /**
     * A container-name test, matched against the title the box's own screen would show.
     *
     * <p>That title is the box's custom name, or the translated {@code container.shulkerBox} for a
     * box nobody renamed - not "Purple Shulker Box". Some packs depend on exactly that: Shulker UI
     * blanks the translation and tells unnamed boxes apart with {@code name=regex:^$}.</p>
     */
    @FunctionalInterface
    public interface NameMatcher {
        NameMatcher ANY = title -> true;
        NameMatcher NEVER = title -> false;

        boolean matches(String title);

        static NameMatcher all(List<NameMatcher> matchers) {
            if (matchers.isEmpty()) return ANY;
            List<NameMatcher> copy = List.copyOf(matchers);
            return title -> copy.stream().allMatch(matcher -> matcher.matches(title));
        }

        /**
         * OptiFine's {@code name} syntax: a leading {@code !} negates, then {@code pattern:} and
         * {@code ipattern:} are wildcards, {@code regex:} and {@code iregex:} are full-match
         * expressions, and anything else must equal the title exactly.
         */
        static NameMatcher optiFine(String rule) {
            if (rule.startsWith("!")) {
                NameMatcher inner = optiFine(rule.substring(1));
                return title -> !inner.matches(title);
            }
            if (rule.startsWith("pattern:")) return wildcard(rule.substring("pattern:".length()), false);
            if (rule.startsWith("ipattern:")) return wildcard(rule.substring("ipattern:".length()), true);
            if (rule.startsWith("regex:")) return regex(rule.substring("regex:".length()), false);
            if (rule.startsWith("iregex:")) return regex(rule.substring("iregex:".length()), true);
            return exact(rule);
        }

        static NameMatcher exact(String name) {
            return name::equals;
        }

        /** {@code *} is any run of characters and {@code ?} exactly one, as OptiFine reads them. */
        static NameMatcher wildcard(String pattern, boolean ignoreCase) {
            StringBuilder regex = new StringBuilder();
            StringBuilder literal = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char ch = pattern.charAt(i);
                if (ch == '*' || ch == '?') {
                    if (!literal.isEmpty()) regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                    regex.append(ch == '*' ? ".*" : ".");
                } else {
                    literal.append(ch);
                }
            }
            if (!literal.isEmpty()) regex.append(Pattern.quote(literal.toString()));
            return compiled(regex.toString(), ignoreCase);
        }

        static NameMatcher regex(String expression, boolean ignoreCase) {
            return compiled(expression, ignoreCase);
        }

        private static NameMatcher compiled(String expression, boolean ignoreCase) {
            try {
                Pattern pattern = Pattern.compile(expression,
                        ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
                return title -> pattern.matcher(title).matches();
            } catch (PatternSyntaxException e) {
                // Both mods discard a definition they cannot compile, so it never applies.
                return NEVER;
            }
        }
    }
}
