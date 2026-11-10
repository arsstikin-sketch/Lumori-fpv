package ru.fpvdrone.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Пакеты между модом и плагином. Внутри — просто байты (формат описан в DroneProtocol),
 * чтобы плагин на Paper мог читать их обычным ByteBuffer без Fabric.
 */
public final class DronePayloads {
    public static final String NAMESPACE = "fpvdrone";

    private DronePayloads() {
    }

    private static byte[] readAll(FriendlyByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    /** Клиент -> сервер: состояние дрона, ~20 раз в секунду. */
    public record State(byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<State> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(NAMESPACE, "state"));
        public static final StreamCodec<FriendlyByteBuf, State> CODEC =
            StreamCodec.of((buf, payload) -> buf.writeBytes(payload.data()), buf -> new State(readAll(buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Клиент -> сервер: «у меня есть мод», версия протокола. */
    public record Hello(byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Hello> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(NAMESPACE, "hello"));
        public static final StreamCodec<FriendlyByteBuf, Hello> CODEC =
            StreamCodec.of((buf, payload) -> buf.writeBytes(payload.data()), buf -> new Hello(readAll(buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Клиент -> сервер: цвета частей дрона (7 байт, номера цветов). */
    public record Colors(byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Colors> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(NAMESPACE, "colors"));
        public static final StreamCodec<FriendlyByteBuf, Colors> CODEC =
            StreamCodec.of((buf, payload) -> buf.writeBytes(payload.data()), buf -> new Colors(readAll(buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Регистрация типов пакетов. Вызывается один раз из общей точки входа (и на клиенте, и на сервере). */
    public static void registerTypes() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(State.TYPE, State.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(Hello.TYPE, Hello.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(Colors.TYPE, Colors.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(Config.TYPE, Config.CODEC);
    }

    /** Сервер -> клиент: настройки сервера (дальность и т.п.). */
    public record Config(byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Config> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(NAMESPACE, "config"));
        public static final StreamCodec<FriendlyByteBuf, Config> CODEC =
            StreamCodec.of((buf, payload) -> buf.writeBytes(payload.data()), buf -> new Config(readAll(buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
