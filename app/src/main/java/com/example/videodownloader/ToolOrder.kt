package com.example.videodownloader

/**
 * 工具顺序的纯逻辑：把用户拖拽持久化的顺序，合并到当前默认工具列表上。
 *
 * 规则（与原 MainActivity.applyPersistedOrder 行为完全一致，便于单测）：
 *  1. 用户保存过的 id，按保存时的相对顺序排在前面；
 *  2. 保存列表里已不存在（旧版本残留）的 id 直接丢弃；
 *  3. 新增的工具（默认列表里有、但保存列表里没有）按默认顺序追加到末尾。
 *
 * 纯函数、不依赖 Android，可直接在 src/test 下单测。
 */
object ToolOrder {

    /**
     * @param defaultIds 当前版本默认工具的 id 顺序
     * @param savedIds   用户上次保存的顺序（来自 SharedPreferences，可能含旧 id）
     * @return 合并后的最终 id 顺序，保证是 defaultIds 的一个排列（不增不减）
     */
    fun reconcile(defaultIds: List<String>, savedIds: List<String>): List<String> {
        if (savedIds.isEmpty()) return defaultIds.toList()

        // LinkedHashMap 保留 defaultIds 的出现顺序，用于"追加新工具"
        val remaining = LinkedHashMap<String, Boolean>()
        defaultIds.forEach { remaining[it] = true }

        val result = ArrayList<String>(defaultIds.size)
        for (id in savedIds) {
            if (remaining.remove(id) != null) result.add(id)
        }
        result.addAll(remaining.keys)
        return result
    }
}
