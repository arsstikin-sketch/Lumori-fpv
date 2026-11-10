package ru.fpvdrone;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.fpvdrone.net.DronePayloads;
import ru.fpvdrone.server.DroneServer;

/**
 * Общая точка входа (и клиент, и сервер). Регистрирует пакеты и серверную часть:
 * если мод стоит на Fabric-сервере, игроки с модом видят дроны друг друга без всяких плагинов.
 */
public final class FpvDroneMod implements ModInitializer {
    public static final String MOD_ID = "fpvdrone";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        DronePayloads.registerTypes();
        DroneServer.init();
        LOGGER.info("[fpvdrone] FPV Drone (общая часть) загружен");
    }
}
