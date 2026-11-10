package ru.fpvdrone.test;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

/** In-memory network sink; tests CraftPlayer paths without a GUI client. */
public final class NativePlayer {
    public static final List<Packet<?>> packets = new ArrayList<>();
    public static Player create() {
        var server = ((CraftServer) Bukkit.getServer()).getServer();
        var world = ((CraftWorld) Bukkit.getWorlds().getFirst()).getHandle();
        var profile = new GameProfile(new UUID(0, 456), "SmokeNative");
        var player = new ServerPlayer(server, world, profile, ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { packets.add(packet); }
            @Override public boolean isConnected() { return true; }
        };
        connection.channel = new EmbeddedChannel();
        connection.address = new InetSocketAddress("127.0.0.1", 12345);
        player.connection = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false));
        var at = world.getWorld().getSpawnLocation().add(0, 3, 0);
        player.setPos(at.getX(), at.getY(), at.getZ());
        world.addNewPlayer(player);
        server.getPlayerList().getPlayers().add(player);
        server.getPlayerList().getPlayersByUUID().put(profile.id(), player);
        player.getBukkitEntity().setOp(true);
        return player.getBukkitEntity();
    }
}
