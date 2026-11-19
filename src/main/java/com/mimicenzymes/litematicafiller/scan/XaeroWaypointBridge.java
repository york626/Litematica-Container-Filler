package com.mimicenzymes.litematicafiller.scan;

import com.mimicenzymes.litematicafiller.config.Configs;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

/**
 * 【野外材料感知 P2】把资源簇画到 Xaero 世界地图/小地图（Ray 2026-11-19 定稿）。
 *
 * <p>用 Xaero 的**第三方 waypoint**（不写玩家 waypoint 文件、不动存档）：
 * <pre>
 * BuiltInHudModules.MINIMAP.getCurrentSession()
 *   → getWorldState().getCurrentWorldPath()
 *   → getWorldManager().getWorldContainer(path)
 *   → getThirdPartyWaypointManager().get(Identifier("litematica_container_filler:material_clusters"))
 *   → clear() + add(id, new Waypoint(x,y,z,name,initials,红))
 * </pre>
 *
 * <p>⚠️ 反射实现（不编译依赖 Xaero、不打包）；没装 Xaero 时 {@link #available()} 为 false，整个功能静默跳过。
 * 第三方点不持久 ⇒ 进世界/切维度/每次重算后由调用方重新灌一遍。
 */
public final class XaeroWaypointBridge {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("litematica_container_filler");

    private static final Identifier ORIGIN = Identifier.of("litematica_container_filler", "material_clusters");
    /** Xaero 颜色索引：12 = 红色。 */
    private static final int COLOR_RED = 12;

    private static Boolean available;
    private static Method getCurrentSession;
    private static Method getWorldState;
    private static Method getCurrentWorldPath;
    private static Method getWorldManager;
    private static Method getWorldContainer;
    private static Method getThirdPartyWaypointManager;
    private static Method managerGet;
    private static Method storageClear;
    private static Method storageAdd;
    private static Method storageSetEnabledGetter;
    private static Constructor<?> waypointCtor;
    private static Class<?> waypointClass;
    private static Object module;
    private static Object storage;
    private static Supplier<Boolean> enabledGetter;

    private XaeroWaypointBridge() {
    }

    public static boolean available() {
        if (available != null) {
            return available;
        }
        try {
            if (!FabricLoader.getInstance().isModLoaded("xaerominimap")) {
                available = false;
                return false;
            }
            Class<?> builtIn = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Field minimapField = builtIn.getField("MINIMAP");
            module = minimapField.get(null);
            Class<?> hudModule = Class.forName("xaero.hud.module.HudModule");
            getCurrentSession = hudModule.getMethod("getCurrentSession");

            Class<?> sessionClass = Class.forName("xaero.hud.minimap.module.MinimapSession");
            getWorldState = sessionClass.getMethod("getWorldState");
            getWorldManager = sessionClass.getMethod("getWorldManager");

            Class<?> worldStateClass = Class.forName("xaero.hud.minimap.world.state.MinimapWorldState");
            getCurrentWorldPath = worldStateClass.getMethod("getCurrentWorldPath");

            Class<?> pathClass = Class.forName("xaero.hud.path.XaeroPath");
            Class<?> worldManagerClass = Class.forName("xaero.hud.minimap.world.MinimapWorldManager");
            getWorldContainer = worldManagerClass.getMethod("getWorldContainer", pathClass);

            Class<?> containerClass = Class.forName("xaero.hud.minimap.world.container.MinimapWorldContainer");
            getThirdPartyWaypointManager = containerClass.getMethod("getThirdPartyWaypointManager");

            Class<?> managerClass = Class.forName("xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypointManager");
            managerGet = managerClass.getMethod("get", Identifier.class);

            Class<?> storageClass = Class.forName("xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints");
            storageClear = storageClass.getMethod("clear");
            storageAdd = storageClass.getMethod("add", String.class,
                    Class.forName("xaero.common.minimap.waypoints.Waypoint"));
            storageSetEnabledGetter = storageClass.getMethod("setEnabledStateGetter", Supplier.class);

            waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            waypointCtor = waypointClass.getConstructor(int.class, int.class, int.class,
                    String.class, String.class, int.class);
            available = true;
        } catch (Throwable t) {
            available = false;
        }
        return available;
    }

    /** 把资源簇灌进 Xaero（先清空自己的 origin 再重加）；未装/没在世界里就跳过。 */
    public static void refresh(List<MaterialClusters.Cluster> clusters) {
        if (!available()) {
            return;
        }
        try {
            Object target = storage();
            if (target == null) {
                return;
            }
            storageClear.invoke(target);
            if (!featuresEnabled()) {
                return;
            }
            int id = 0;
            for (MaterialClusters.Cluster cluster : clusters) {
                if (cluster == null || cluster.count() <= 0) {
                    continue;
                }
                String name = itemName(cluster.item()) + "×" + cluster.count();
                Object waypoint = waypointCtor.newInstance(cluster.x(), cluster.y(), cluster.z(),
                        name, initials(cluster.item()), COLOR_RED);
                storageAdd.invoke(target, "cluster_" + (id++), waypoint);
            }
            if (enabledGetter == null) {
                enabledGetter = XaeroWaypointBridge::featuresEnabled;
                storageSetEnabledGetter.invoke(target, enabledGetter);
            }
        } catch (Throwable t) {
            LOGGER.warn("[material-scan] 资源簇 waypoint 更新失败：{}", t.toString());
        }
    }

    /** 切维度/换世界后清掉缓存的 session 存储（下次 refresh 重新解析）。 */
    public static void resetSession() {
        storage = null;
    }

    /** 清掉自己的全部资源点（关开关/清空时用）。 */
    public static void clear() {
        if (!available()) {
            return;
        }
        try {
            Object target = storage();
            if (target != null) {
                storageClear.invoke(target);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean featuresEnabled() {
        return Configs.MATERIAL_SCAN_ENABLED.getBooleanValue()
                && Configs.MATERIAL_SCAN_CLUSTERS_ENABLED.getBooleanValue();
    }

    private static Object storage() throws Exception {
        if (storage != null) {
            return storage;
        }
        Object session = getCurrentSession.invoke(module);
        if (session == null) {
            return null;
        }
        Object worldState = getWorldState.invoke(session);
        Object path = worldState == null ? null : getCurrentWorldPath.invoke(worldState);
        if (path == null) {
            return null;
        }
        Object worldManager = getWorldManager.invoke(session);
        if (worldManager == null) {
            return null;
        }
        Object container = getWorldContainer.invoke(worldManager, path);
        if (container == null) {
            return null;
        }
        Object manager = getThirdPartyWaypointManager.invoke(container);
        if (manager == null) {
            return null;
        }
        storage = managerGet.invoke(manager, ORIGIN);
        return storage;
    }

    private static String itemName(Item item) {
        return new net.minecraft.item.ItemStack(item).getName().getString();
    }

    private static String initials(Item item) {
        String path = Registries.ITEM.getId(item).getPath();
        String letters = path.replaceAll("[^a-z]", "");
        if (letters.length() >= 2) {
            return letters.substring(0, 2).toUpperCase();
        }
        return "RC";
    }
}
