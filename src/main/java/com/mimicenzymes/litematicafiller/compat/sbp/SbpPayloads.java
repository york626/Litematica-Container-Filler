package com.mimicenzymes.litematicafiller.compat.sbp;

import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【快捷精妙背包提取】的网络层。
 *
 * <p>精妙背包的内容只有服务端可见，客户端没法像潜影盒那样直接读 ⇒ 必须"请求服务端把物品抽到
 * 玩家背包"。这里定义一对 C2S/S2C 包；只有装了精妙背包时才注册（软依赖）。
 */
public final class SbpPayloads {
    private SbpPayloads() {
    }

    private static boolean registered = false;

    /** C2S：请求从玩家佩戴/手持的精妙背包里取出 {@code count} 个 {@code itemId}。 */
    public record Extract(Identifier itemId, int count) implements CustomPayload {
        public static final Id<Extract> ID = new Id<>(Identifier.of("litematica_container_filler", "sbp_extract"));

        public static final PacketCodec<RegistryByteBuf, Extract> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, p -> p.itemId.toString(),
                PacketCodecs.VAR_INT, Extract::count,
                (idStr, count) -> new Extract(Identifier.of(idStr), count));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }

        public static void receive(Extract payload, ServerPlayNetworking.Context context) {
            if (!SbpSupport.available()) {
                return;
            }
            context.player().getServer().execute(() ->
                    SbpServerExtract.extract(context.player(), payload.itemId(), payload.count()));
        }
    }

    /** S2C：报告某个物品的取货是否成功（带 itemId，便于按物品去重/退避）。 */
    public record ExtractResponse(Identifier itemId, boolean success) implements CustomPayload {
        public static final Id<ExtractResponse> ID =
                new Id<>(Identifier.of("litematica_container_filler", "sbp_extract_response"));

        public static final PacketCodec<RegistryByteBuf, ExtractResponse> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, p -> p.itemId.toString(),
                PacketCodecs.BOOL, ExtractResponse::success,
                (idStr, success) -> new ExtractResponse(Identifier.of(idStr), success));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }

        public static void receive(ExtractResponse payload, ClientPlayNetworking.Context context) {
            SbpBridge.onResponse(payload.itemId(), payload.success());
        }
    }

    /** 注册（幂等）。没装精妙背包时什么都不做。 */
    public static void register() {
        if (registered || !SbpSupport.available()) {
            return;
        }
        registered = true;
        PayloadTypeRegistry.playC2S().register(Extract.ID, Extract.CODEC);
        PayloadTypeRegistry.playS2C().register(ExtractResponse.ID, ExtractResponse.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Extract.ID, Extract::receive);
        ClientPlayNetworking.registerGlobalReceiver(ExtractResponse.ID, ExtractResponse::receive);
    }

    /** 给 {@link SbpSupport} 用：客户端能不能发。 */
    static boolean canSend() {
        try {
            return registered && ClientPlayNetworking.canSend(Extract.ID);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void send(Identifier itemId, int count) {
        ClientPlayNetworking.send(new Extract(itemId, count));
    }

    static Optional<UUID> unused() {
        return Optional.empty();
    }
}
