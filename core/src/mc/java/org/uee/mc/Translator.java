package org.uee.mc;

/**
 * Resolves translation keys to display names in a requested locale.
 *
 * <p>Name resolution is the one place where client and server genuinely diverge, so it is abstracted
 * rather than assumed. A client can ask the loaded language tables directly; a dedicated server has
 * no client language state and must read language files from the resource manager. Both paths return
 * the same strings.
 *
 * <p>Two locales are requested because an export carries both a localized and an English name: a
 * wiki entry needs the localized name when one exists and the English name as the fallback, and the
 * two come from separate language tables.
 *
 * <p>Returning {@code null} matters: it is what keeps the "never translate on the user's behalf"
 * rule enforceable. A missing localization must surface as an absent name, not as a guess.
 */
public interface Translator {

    /** Locale tag for Simplified Chinese, matching the language file name. */
    String ZH_CN = "zh_cn";
    /** Locale tag for English (US), matching the language file name. */
    String EN_US = "en_us";

    /**
     * @param key a translation key such as {@code block.minecraft.stone}
     * @param locale one of {@link #ZH_CN} or {@link #EN_US}
     * @return the localized name, or {@code null} when no translation is available
     */
    String translate(String key, String locale);

    /** A translator that never resolves anything — used when language data is unavailable. */
    static Translator none() {
        return (key, locale) -> null;
    }
}
