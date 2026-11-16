package com.mimicenzymes.litematicafiller.materials;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 材料列表的诊断日志（2026-11-16 从 Ray 工具箱迁入，批次 1）。
 *
 * <p>放在普通类里而不是 Mixin 里：Mixin 的静态初始化不可靠（工具箱 v1.30.14 的「已生效」
 * 日志就是这么丢的），而这里的 {@code LOGGED} 必须能正常初始化。
 */
public final class MaterialListDiagnostics {

    private static final Logger LOGGER = LoggerFactory.getLogger("litematica_container_filler");

    /** 「取区块时忽略放置启用」只打一次。 */
    private static final AtomicBoolean IGNORE_PLACEMENT_LOGGED = new AtomicBoolean();

    /** 「schematicWorld 为空也放行统计任务」只打一次。 */
    private static final AtomicBoolean EXECUTE_WITHOUT_WORLD_LOGGED = new AtomicBoolean();

    private MaterialListDiagnostics() {
    }

    /** 判据：「忽略放置启用」真的接管了取区块（改 ANY）时打一次。 */
    public static void logIgnorePlacementOnce() {
        if (IGNORE_PLACEMENT_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("[material-list] 材料统计：已忽略「放置启用」取区块（改 ANY）");
        }
    }

    /** 判据：放置全关、schematicWorld 为空时，统计任务仍被放行（否则清单会被写成空）。 */
    public static void logExecuteWithoutSchematicWorldOnce() {
        if (EXECUTE_WITHOUT_WORLD_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("[material-list] 材料统计：放置全关（schematicWorld 为空）⇒ 仍放行任务，方块状态改读投影数据");
        }
    }

    /** v1.18.3 诊断：每次构造统计任务都打一行（区块盒子数 = 任务到底有没有活儿干）。 */
    public static void logTaskBoxes(String required, int boxes, boolean configOn) {
        LOGGER.info("[material-list] 统计任务取区块：{} → {}，盒子 {} 个",
                required, configOn ? "ANY" : required, boxes);
    }

    /**
     * v1.18.3 诊断：任务收尾时打「任务类型 / 清单条数 / 本次采样次数」——
     * 用来区分「任务没跑」「跑了但采样全空」「跑了但结果被别处清掉」。
     */
    public static void logTaskStop(String taskName, boolean materialListPresent, int listSize,
                                   long sampleCalls, long sampleHits) {
        LOGGER.info("[material-list] 材料统计任务结束：{}｜清单已就绪={}｜清单 {} 条｜采样 {}/{} 次命中",
                taskName, materialListPresent, listSize, sampleHits, sampleCalls);
    }
}
