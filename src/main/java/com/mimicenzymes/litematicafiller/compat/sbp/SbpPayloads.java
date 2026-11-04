package com.mimicenzymes.litematicafiller.compat.sbp;

import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【快捷精妙背包提取】的网络层。
 *
 * <p>精妙背包内容只有服务端可见 ⇒ 必须"请求服务端搬东西"。两类请求：
 * <ul>
 *   <li>{@link Extract}：把材料（含"背包里潜影盒内的材料"）搬到玩家背包；</li>
 *   <li>{@link Store}：背包满时把多余物品塞回精妙背包（"有序存放"的背包版）。</li>
 * </ul>
 * 回包统一带 reason：0=成功 / 1=没找到 / 2=空间不足。
 */
public final class SbpPayloads {
    private SbpPayloads() {
    }

    private static boolean registered = false;

    public static final int OK = 0;
    public static final int NOT_FOUND = 1;
    public static final int NO_SPACE = 2;

    /** C2S：请求从精妙背包取出 {@code count} 个 {@code itemId}（背包里没有散装时，会把装它的潜影盒整盒搬到玩家背包）。 */
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

    /** C2S：请求把 {@code count} 个 {@code itemId} 从玩家背包塞回精妙背包（腾位置用）。 */
    public record Store(Identifier itemId, int count) implements CustomPayload {
        public static final Id<Store> ID = new Id<>(Identifier.of("litematica_container_filler", "sbp_store"));

        public static final PacketCodec<RegistryByteBuf, Store> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, p -> p.itemId.toString(),
                PacketCodecs.VAR_INT, Store::count,
                (idStr, count) -> new Store(Identifier.of(idStr), count));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }

        public static void receive(Store payload, ServerPlayNetworking.Context context) {
            if (!SbpSupport.available()) {
                return;
            }
            context.player().getServer().execute(() ->
                    SbpServerExtract.store(context.player(), payload.itemId(), payload.count()));
        }
    }

    /** S2C：结果（reason 见常量）。payload = 服务端"代为腾位"的物品列表（id=count;…），客户端据此登记待取回。 */
    public record Result(Identifier itemId, int reason, String stashed) implements CustomPayload {
        public static final Id<Result> ID =
                new Id<>(Identifier.of("litematica_container_filler", "sbp_result"));

        public static final PacketCodec<RegistryByteBuf, Result> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, p -> p.itemId.toString(),
                PacketCodecs.VAR_INT, Result::reason,
                PacketCodecs.STRING, r -> r.stashed() == null ? "" : r.stashed(),
                (idStr, reason, stashed) -> new Result(Identifier.of(idStr), reason, stashed));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }

        public static void receive(Result payload, ClientPlayNetworking.Context context) {
            SbpBridge.onResponse(payload.itemId(), payload.reason(), payload.stashed());
        }
    }

    /** 注册（幂等）。没装精妙背包时什么都不做。 */
    public static void register() {
        if (registered || !SbpSupport.available()) {
            return;
        }
        registered = true;
        PayloadTypeRegistry.playC2S().register(Extract.ID, Extract.CODEC);
        PayloadTypeRegistry.playC2S().register(Store.ID, Store.CODEC);
        PayloadTypeRegistry.playS2C().register(Result.ID, Result.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Extract.ID, Extract::receive);
        ServerPlayNetworking.registerGlobalReceiver(Store.ID, Store::receive);
        ClientPlayNetworking.registerGlobalReceiver(Result.ID, Result::receive);
    }

    static boolean canSend() {
        try {
            return registered && ClientPlayNetworking.canSend(Extract.ID);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void sendExtract(Identifier itemId, int count) {
        ClientPlayNetworking.send(new Extract(itemId, count));
    }

    static void sendStore(Identifier itemId, int count) {
        ClientPlayNetworking.send(new Store(itemId, count));
    }

    static Optional<Object> unused() {
        return Optional.empty();
    }
}
