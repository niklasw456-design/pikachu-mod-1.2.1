package de.texturemixer;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import de.texturemixer.net.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client-Seite: welche Spieler haben die Mod, und das Icon am Nametag. */
public final class NameIcon {
    private NameIcon() {
    }

    public static final String OFF = "off";
    public static final String BEFORE = "before";
    public static final String AFTER = "after";

    private static final Set<UUID> USERS = ConcurrentHashMap.newKeySet();
    /** Nametag-Farben (RGB) der anderen Mod-Spieler, vom Server geschickt. */
    private static final Map<UUID, Net.NameStyle> COLORS = new ConcurrentHashMap<>();
    private static Text icon;

    public static void setUsers(Iterable<UUID> users) {
        USERS.clear();
        for (UUID u : users) USERS.add(u);
    }

    public static void clear() {
        USERS.clear();
        COLORS.clear();
    }

    public static void setColors(Map<UUID, Net.NameStyle> colors) {
        COLORS.clear();
        COLORS.putAll(colors);
    }

    /** Schickt die eigene Nametag-Farbe an den Server (der sie an alle Mod-Spieler verteilt). Ohne Mod auf dem Server passiert nichts. */
    public static void sendColor() {
        if (!ClientPlayNetworking.canSend(Net.NameColorC2S.ID)) return;
        ClientPlayNetworking.send(new Net.NameColorC2S(MixerConfig.get().nameColor, MixerConfig.get().nameIgn));
    }

    /** Farbe des Nametags dieses Spielers (RGB) oder null = Standard. Der eigene Spieler nimmt die Einstellung direkt. */
    private static Integer colorOf(UUID id) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.player.getUuid().equals(id)) {
            int own = MixerConfig.get().nameColor;
            return own >= 0 ? Integer.valueOf(own) : null;
        }
        Net.NameStyle st = COLORS.get(id);
        return st == null ? null : Integer.valueOf(st.color());
    }

    /** Der Ingame-Name, der gefärbt wird: der eingegebene IGN, sonst der echte Name des Spielers. */
    private static String ignOf(UUID id, String realName) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String ign;
        if (mc.player != null && mc.player.getUuid().equals(id)) {
            ign = MixerConfig.get().nameIgn;
        } else {
            Net.NameStyle st = COLORS.get(id);
            ign = st == null ? null : st.ign();
        }
        return ign == null || ign.isBlank() ? realName : ign.trim();
    }

    /**
     * Färbt NUR das Vorkommen von {@code ign} im Namen ein. Alles andere (Rang, Prefix, Suffix, Teamfarbe)
     * behält seinen Stil. Kommt der IGN nicht vor, bleibt der Text unverändert. Bei mehreren Treffern
     * zählt der letzte (der Name steht fast immer hinter dem Rang).
     */
    public static Text recolor(Text name, String ign, int color) {
        if (color < 0 || ign == null || ign.isEmpty()) return name;
        List<Style> styles = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        name.visit((style, str) -> {
            styles.add(style);
            parts.add(str);
            all.append(str);
            return java.util.Optional.<Void>empty();
        }, Style.EMPTY);

        int start = -1;
        for (int i = all.length() - ign.length(); i >= 0; i--) {
            if (all.toString().regionMatches(true, i, ign, 0, ign.length())) {
                start = i;
                break;
            }
        }
        if (start < 0) return name;
        int end = start + ign.length();

        MutableText out = Text.empty();
        int pos = 0;
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            Style st = styles.get(i);
            int ps = pos;
            int pe = pos + part.length();
            pos = pe;
            int os = Math.max(ps, start);
            int oe = Math.min(pe, end);
            if (os >= oe) {
                if (!part.isEmpty()) out.append(Text.literal(part).setStyle(st));
                continue;
            }
            if (os > ps) out.append(Text.literal(part.substring(0, os - ps)).setStyle(st));
            out.append(Text.literal(part.substring(os - ps, oe - ps)).setStyle(st.withColor(color)));
            if (oe < pe) out.append(Text.literal(part.substring(oe - ps)).setStyle(st));
        }
        return out;
    }

    /** Nächster Zustand des Reglers: vor -> hinter -> aus -> vor. */
    public static String next(String mode) {
        if (BEFORE.equals(mode)) return AFTER;
        if (AFTER.equals(mode)) return OFF;
        return BEFORE;
    }

    public static String label(String mode) {
        if (BEFORE.equals(mode)) return "Icon: vor Name";
        if (AFTER.equals(mode)) return "Icon: hinter Name";
        return "Icon: aus";
    }

    private static Text icon() {
        if (icon == null) {
            String json = "{\"text\":\"\\uE000\",\"font\":\"texturemixer:icon\",\"color\":\"white\"}";
            icon = TextCodecs.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).result().orElse(Text.empty());
        }
        return icon;
    }

    private static boolean isModUser(UUID id) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.player.getUuid().equals(id)) return true; // ich selbst habe die Mod ja
        return USERS.contains(id);
    }

    /** Gibt den Namen mit eingefärbtem IGN und Icon zurück (je nach Einstellung und ob der Spieler die Mod hat). */
    public static Text decorate(Text name, UUID player, String realName) {
        if (name.getString().indexOf('\uE000') >= 0) return name; // schon dekoriert, nie doppelt
        Integer color = colorOf(player);
        if (color != null) {
            name = recolor(name, ignOf(player, realName), color); // nur der IGN, Rang bleibt wie er ist
        }
        String mode = MixerConfig.get().nameIcon;
        if (mode == null || OFF.equals(mode) || !isModUser(player)) return name;
        Text sp = Text.literal(" ");
        if (BEFORE.equals(mode)) return Text.empty().append(icon()).append(sp).append(name);
        return Text.empty().append(name).append(sp).append(icon());
    }
}
