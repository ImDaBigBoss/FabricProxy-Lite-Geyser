package one.oktw;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import one.oktw.mixin.core.ClientConnection_AddressAccessor;
import one.oktw.mixin.core.ServerLoginNetworkHandlerAccessor;
import one.oktw.utils.GeyserSkinGetter;
import one.oktw.utils.platforms.PlatformUtil;
import org.apache.logging.log4j.LogManager;

import java.util.UUID;

class PacketHandler {
    private final ModConfig config;
    private final PlatformUtil platformUtil;

    PacketHandler(ModConfig config, PlatformUtil platformUtil) {
        this.config = config;
        this.platformUtil = platformUtil;
    }

    void handleVelocityPacket(MinecraftServer server, ServerLoginNetworkHandler handler, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer synchronizer, PacketSender ignored) {
        if (understood) {
            this.javaLogin(server, handler, buf, synchronizer);
            return;
        }

        if (this.platformUtil != null) {
            String name = ((ServerLoginNetworkHandlerAccessor) handler).getProfile().getName();
            Long xuid = platformUtil.getXuid(name);
            if (xuid != null) {
                this.bedrockLogin(server, handler, synchronizer, name, xuid);
                return;
            }
        }

        handler.disconnect(Text.of(config.getAbortedMessage()));
    }

    private void javaLogin(MinecraftServer server, ServerLoginNetworkHandler handler, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer synchronizer) {
        synchronizer.waitFor(server.submit(() -> {
            try {
                if (!VelocityLib.checkIntegrity(buf)) {
                    handler.disconnect(Text.of("Unable to verify player details"));
                    return;
                }
                VelocityLib.checkVersion(buf);
            } catch (Throwable e) {
                LogManager.getLogger().error("Secret check failed.", e);
                handler.disconnect(Text.of("Unable to verify player details"));
                return;
            }

            ClientConnection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();
            ((ClientConnection_AddressAccessor) connection).setAddress(new java.net.InetSocketAddress(VelocityLib.readAddress(buf), ((java.net.InetSocketAddress) (connection.getAddress())).getPort()));

            GameProfile profile;
            try {
                profile = VelocityLib.createProfile(buf);
            } catch (Exception e) {
                LogManager.getLogger().error("Profile create failed.", e);
                handler.disconnect(Text.of("Unable to read player profile"));
                return;
            }

            if (config.getHackEarlySend()) {
                handler.onHello(new LoginHelloC2SPacket(profile.getName(), profile.getId()));
            }

            ((ServerLoginNetworkHandlerAccessor) handler).setProfile(profile);
        }));
    }

    private void bedrockLogin(MinecraftServer server, ServerLoginNetworkHandler handler, ServerLoginNetworking.LoginSynchronizer synchronizer, String name, long xuid) {
        synchronizer.waitFor(server.submit(() -> {
            UUID playerUUID = new UUID(0, xuid);

            String playerName = name;
            if (!playerName.startsWith(this.platformUtil.getBedrockPlayerPrefix())) {
                playerName = this.platformUtil.getBedrockPlayerPrefix() + playerName;
            }
            //Java only allows 16 characters and probably shouldn't have spaces
            playerName = playerName.replace(" ", "_");
            if (playerName.length() > 16) {
                playerName = playerName.substring(0, 16);
            }

            GameProfile profile = new GameProfile(playerUUID, playerName);

            GeyserSkinGetter.SkinData skinData = GeyserSkinGetter.getSkin(xuid);
            if (skinData != null) {
                profile.getProperties().removeAll("textures");
                profile.getProperties().put("textures", new Property("textures", skinData.value, skinData.signature));
            }

            ((ServerLoginNetworkHandlerAccessor) handler).setProfile(profile);
        }));
    }
}
