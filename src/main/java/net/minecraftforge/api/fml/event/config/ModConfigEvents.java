package net.minecraftforge.api.fml.event.config;

import com.google.common.collect.Maps;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraftforge.fml.config.ModConfig;

import java.util.Map;
import java.util.Objects;

/**
 * Mod config events adapted for Fabric's callback event style.
 *
 * <p>Unlike on Forge there are is no mod event bus for firing mod specific events on Fabric meaning general callbacks would be fired for every mod for every config which is undesirable.
 * To solve this issue without introducing a manual mod id check in the events implementation itself, mod config event callbacks are instead created separately for every mod that has configs registered with Forge Config Api Port.
 * Accessing events for a specific mod is done by calling {@link #loading(String)} or {@link #reloading(String)}.
 *
 * <p>Package is purposefully a different one from Forge, as the class itself works completely differently and is not compatible with the original implementation.
 */
public final class ModConfigEvents {

    /**
     * access to mod specific loading event
     *
     * @param modId     the mod id to access config event for
     * @return          the {@link Loading} event
     */
    public static Event<Loading> loading(String modId) {
        Objects.requireNonNull(modId, "mod id is null");
        return ModConfigEventsHolder.modSpecific(modId).loading();
    }

    /**
     * access to mod specific reloading event
     *
     * @param modId     the mod id to access config event for
     * @return          the {@link Reloading} event
     */
    public static Event<Reloading> reloading(String modId) {
        Objects.requireNonNull(modId, "mod id is null");
        return ModConfigEventsHolder.modSpecific(modId).reloading();
    }

    private ModConfigEvents() {

    }

    /**
     * Called when a config is loaded or unloaded (unloading only applies for server configs)
     */
    @FunctionalInterface
    public interface Loading {

        /**
         * @param config    the mod config that is loading
         */
        void onModConfigLoading(ModConfig config);

    }

    /**
     * Called when a config is reloaded which happens when the file is updated by ConfigWatcher and when it is synced from the server
     */
    @FunctionalInterface
    public interface Reloading {

        /**
         * @param config    the mod config that is reloading
         */
        void onModConfigReloading(ModConfig config);

    }

    /**
     * internal mod specific event storage
     *
     * @param modId             the mod
     * @param loading           loading event
     * @param reloading         reloading event
     */
    record ModConfigEventsHolder(String modId, Event<Loading> loading, Event<Reloading> reloading) {
        /**
         * internal storage for mod specific config events
         */
        private static final Map<String, ModConfigEventsHolder> MOD_SPECIFIC_EVENT_HOLDERS = Maps.newConcurrentMap();

        /**
         * internal access to mod specific config events
         *
         * <p>the method is synchronized as access from different threads is possible (e.g. the config watcher thread)
         *
         * @param modId     the mod id to access config events for
         * @return          access to a holder with both mod specific {@link Loading} and {@link Reloading} events
         */
        synchronized static ModConfigEventsHolder modSpecific(String modId) {
            return MOD_SPECIFIC_EVENT_HOLDERS.computeIfAbsent(modId, ModConfigEventsHolder::create);
        }

        /**
         * creates a new holder duh
         *
         * @param modId     the mod
         * @return          holder with newly created events
         */
        private static ModConfigEventsHolder create(String modId) {
            Event<Loading> loading = EventFactory.createArrayBacked(Loading.class, listeners -> config -> {
                for (Loading event : listeners) {
                    event.onModConfigLoading(config);
                }
            });
            Event<Reloading> reloading = EventFactory.createArrayBacked(Reloading.class, listeners -> config -> {
                for (Reloading event : listeners) {
                    event.onModConfigReloading(config);
                }
            });
            return new ModConfigEventsHolder(modId, loading, reloading);
        }
    }
}
