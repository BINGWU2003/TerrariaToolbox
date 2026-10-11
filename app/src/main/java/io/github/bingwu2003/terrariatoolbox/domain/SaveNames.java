package io.github.bingwu2003.terrariatoolbox.domain;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Shared by the application and the restricted Shizuku service. */
public final class SaveNames {
    private SaveNames() { }
    public static String directory(String name) {
        if (name == null || name.startsWith(".") || name.length() < 5 ||
                name.getBytes(StandardCharsets.UTF_8).length > 240 ||
                name.chars().anyMatch(c -> c < 32 || c == 127 || c == '/' || c == '\\'))
            throw new IllegalArgumentException("Invalid save filename");
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".plr")) return "Players";
        if (lower.endsWith(".wld")) return "Worlds";
        throw new IllegalArgumentException("Only .plr and .wld are supported");
    }
    public static String available(String name, Collection<String> existing) {
        directory(name);
        Set<String> occupied = new HashSet<>();
        for (String item : existing) occupied.add(item.toLowerCase(Locale.ROOT));
        String stem = name.substring(0, name.length() - 4), extension = name.substring(name.length() - 4);
        for (int index = 0; index < 100000; index++) {
            String suffix = index == 0 ? "" : " (" + index + ")";
            String candidateStem = stem;
            while ((candidateStem + suffix + extension).getBytes(StandardCharsets.UTF_8).length > 240)
                candidateStem = candidateStem.substring(0, candidateStem.offsetByCodePoints(candidateStem.length(), -1));
            String candidate = candidateStem + suffix + extension;
            if (!occupied.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        throw new IllegalArgumentException("Too many duplicate saves");
    }
}
