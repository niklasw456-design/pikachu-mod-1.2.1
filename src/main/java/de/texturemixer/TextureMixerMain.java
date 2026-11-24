package de.texturemixer;

import de.texturemixer.net.Net;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Läuft auf Client UND Server (auch im Einzelspieler/LAN). Der Server merkt sich, wer die Mod hat
 * (und welche Nametag-Farbe er gewählt hat) und schickt allen Mod-Spielern die Listen. Ohne die Mod auf dem Server sieht man nur das eigene Icon.
 */
public class TextureMixerMain implements ModInitializer {
    private static final Set<UUID> MOD_USERS = ConcurrentHashMap.newKeySet();
    /** Nametag-Farben (RGB) der Mod-Spieler, die eine Farbe gewählt haben. */
    private static final Map<UUID, Net.NameStyle> NAME_COLORS = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(Net.HelloC2S.ID, Net.HelloC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(Net.UsersS2C.ID, Net.UsersS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(Net.NameColorC2S.ID, Net.NameColorC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(Net.NameColorsS2C.ID, Net.NameColorsS2C.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(Net.HelloC2S.ID, (payload, context) -> {
            MOD_USERS.add(context.player().getUuid());
            broadcast(context.server(), null);
        });

        ServerPlayNetworking.registerGlobalReceiver(Net.NameColorC2S.ID, (payload, context) -> {
            UUID id = context.player().getUuid();
            int color = payload.color();
            String ign = payload.ign() == null ? "" : payload.ign().trim();
            if (ign.length() > 16) ign = ign.substring(0, 16);
            boolean changed;
            if (color < 0 || color > 0xFFFFFF) {
                changed = NAME_COLORS.remove(id) != null; // Standard
            } else {
                Net.NameStyle old = NAME_COLORS.put(id, new Net.NameStyle(color, ign));
                changed = old == null || old.color() != color || !old.ign().equals(ign);
            }
            if (changed) broadcast(context.server(), null);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity leaving = handler.getPlayer();
            boolean hadColor = NAME_COLORS.remove(leaving.getUuid()) != null;
            if (MOD_USERS.remove(leaving.getUuid()) || hadColor) broadcast(server, leaving);
        });
    }

    private static void broadcast(MinecraftServer server, ServerPlayerEntity skip) {
        Net.UsersS2C packet = new Net.UsersS2C(new ArrayList<>(MOD_USERS));
        Net.NameColorsS2C colors = new Net.NameColorsS2C(new LinkedHashMap<>(NAME_COLORS));
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        for (ServerPlayerEntity p : players) {
            if (p == skip) continue;
            if (ServerPlayNetworking.canSend(p, Net.UsersS2C.ID)) ServerPlayNetworking.send(p, packet);
            if (ServerPlayNetworking.canSend(p, Net.NameColorsS2C.ID)) ServerPlayNetworking.send(p, colors);
        }
    }
}
