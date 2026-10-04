package org.uee.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.uee.model.ElementKind;

/**
 * The single place a run's configuration is decided.
 *
 * <p>Three interfaces feed this and nothing else. Rather than three parsers that each build a config
 * their own way, each one records <em>what it was told</em> as a {@link ConfigFile} — the same
 * partial-description type the file parser produces — and the stack is resolved here:
 *
 * <ol>
 *   <li>built-in defaults
 *   <li>the config file
 *   <li>the command line, or whatever the programmatic caller passed
 * </ol>
 *
 * <p>The reason to funnel rather than to branch: with four loaders and three interfaces there would
 * otherwise be twelve paths from "what the user asked for" to "what the pipeline does", and the one
 * that drifted would drift silently. Here there is one path, and a test over it covers every
 * interface at once.
 *
 * <p>This class is also where the vocabulary lives, so a token accepted by the command is the same
 * token accepted in the file.
 */
public final class ConfigResolver {

    private ConfigResolver() {
    }

    /** The outcome of resolving, including anything the user should be told about. */
    public record Resolved(ExportConfig config, List<String> warnings, List<String> errors) {

        public Resolved {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /**
     * Resolves a layered configuration.
     *
     * @param fileLayer the config file, or {@code null} / an empty description
     * @param overrideLayer the command line or programmatic call, or {@code null}
     * @param base the built-in defaults to start from
     */
    public static Resolved resolve(ConfigFile fileLayer, ConfigFile overrideLayer,
            ExportConfig base) {
        List<String> warnings = new ArrayList<>(2);
        List<String> errors = new ArrayList<>(2);

        ExportConfig config = base;
        if (fileLayer != null && !fileLayer.isEmpty()) {
            try {
                config = fileLayer.applyTo(config);
            } catch (RuntimeException e) {
                errors.add("config file: " + e.getMessage());
            }
            // An unrecognised key is reported: a typo that silently does nothing leaves the user
            // believing a setting took effect while the export behaves differently.
            for (String key : fileLayer.unknownKeys()) {
                warnings.add("config file: unknown key '" + key + "' was ignored");
            }
        }
        if (overrideLayer != null && !overrideLayer.isEmpty()) {
            try {
                config = overrideLayer.applyTo(config);
            } catch (RuntimeException e) {
                errors.add("arguments: " + e.getMessage());
            }
        }

        // Analysis kinds without the analysis switch is a contradiction worth naming rather than
        // silently satisfying: the user asked for diagnostic categories but no analysis would
        // produce them, and an empty directory is a confusing way to learn that.
        if (!config.analyze() && containsAnalysisKind(config.kinds())) {
            warnings.add("analysis categories were requested but analyze = false,"
                    + " so no analysis output will be produced");
        }
        // Conversely, asking for analysis without any analysis category produces nothing either.
        if (config.analyze() && !containsAnalysisKind(config.kinds())
                && !containsDataKind(config.kinds())) {
            warnings.add("no categories selected; nothing will be exported");
        }
        return new Resolved(config, warnings, errors);
    }

    /** Resolves the defaults alone, which is what the one-key command runs. */
    public static Resolved resolveDefaults(ExportConfig base) {
        return resolve(null, null, base);
    }

    /** Reads the config file for a config directory, then resolves. */
    public static Resolved resolveFrom(Path configDir, ConfigFile overrideLayer, ExportConfig base)
            throws IOException {
        return resolve(ConfigFile.readFrom(configDir), overrideLayer, base);
    }

    /** The config directory a loader should use, given its game directory. */
    public static Path configDir(Path gameDirectory) {
        return gameDirectory.resolve("config");
    }

    /** The config file path a loader should use. */
    public static Path configFile(Path gameDirectory) {
        return configDir(gameDirectory).resolve(ConfigFile.FILE_NAME);
    }

    // ---------------------------------------------------------------- vocabulary

    /**
     * Parses the category selection a command line expressed.
     *
     * @return a description naming the categories, or {@code null} when a token was not recognised
     */
    public static ConfigFile kindsFromTokens(List<String> tokens) {
        Set<ElementKind> kinds = Tokens.kinds(tokens);
        if (kinds == null) {
            return null;
        }
        return ConfigFile.builder().kinds(kinds).build();
    }

    /**
     * Parses the format selection a command line expressed.
     *
     * @return a description naming the formats, or {@code null} when a token was not recognised
     */
    public static ConfigFile formatsFromTokens(List<String> tokens) {
        Set<String> formats = Tokens.formats(tokens);
        if (formats == null) {
            return null;
        }
        return ConfigFile.builder().formats(formats).build();
    }

    private static boolean containsAnalysisKind(Set<ElementKind> kinds) {
        for (ElementKind k : kinds) {
            if (k.isAnalysis()) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDataKind(Set<ElementKind> kinds) {
        for (ElementKind k : kinds) {
            if (!k.isAnalysis()) {
                return true;
            }
        }
        return false;
    }
}
