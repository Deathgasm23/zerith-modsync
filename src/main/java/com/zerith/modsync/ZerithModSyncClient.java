package com.zerith.modsync;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.concurrent.*;

public final class ZerithModSyncClient implements ClientModInitializer {
    private final ConcurrentLinkedQueue<Runnable> pendingUi = new ConcurrentLinkedQueue<>();

    @Override
    public void onInitializeClient() {
        ModSyncService service = new ModSyncService(FabricLoader.getInstance().getGameDir(), new ModSyncService.Notifier() {
            @Override public void overlay(String t) { show(t, true); }
            @Override public void important(String t) { show(t, false); }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(service::close));
        // Deliver queued messages once a world/player exists; never blocks the render thread.
        ScheduledExecutorService ui = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Zerith ModSync UI");
            t.setDaemon(true);
            return t;
        });
        ui.scheduleWithFixedDelay(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null && !pendingUi.isEmpty()) mc.execute(() -> {
                for (Runnable r; (r = pendingUi.poll()) != null; ) r.run();
            });
        }, 3, 2, TimeUnit.SECONDS);
        Thread starter = new Thread(service::start, "Zerith ModSync start");
        starter.setDaemon(true);
        starter.start();
    }

    private void show(String text, boolean overlay) {
        if (overlay) pendingUi.removeIf(r -> r instanceof Overlay o && o.overlay());
        pendingUi.add(new Overlay(text, overlay));
    }

    private record Overlay(String text, boolean overlay) implements Runnable {
        @Override public void run() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) mc.player.sendMessage(Text.literal(text), overlay);
        }
    }
}
