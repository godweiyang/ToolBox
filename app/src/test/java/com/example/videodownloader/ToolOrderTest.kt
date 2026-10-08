package com.example.videodownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOrderTest {

    private val default = listOf("a", "b", "c", "d", "e")

    @Test fun `empty saved order keeps default order`() {
        assertEquals(default, ToolOrder.reconcile(default, emptyList()))
    }

    @Test fun `saved relative order is preserved`() {
        val saved = listOf("c", "a", "e", "b", "d")
        assertEquals(saved, ToolOrder.reconcile(default, saved))
    }

    @Test fun `stale saved ids are dropped`() {
        val saved = listOf("x", "c", "a", "gone", "b")
        // x/gone 不存在 → 丢弃；剩下的新工具 d,e 按默认顺序追加到末尾
        assertEquals(listOf("c", "a", "b", "d", "e"), ToolOrder.reconcile(default, saved))
    }

    @Test fun `new tools are appended at end in default order`() {
        // 用户旧版本只保存了 a,b,c；新版本新增 d,e
        val saved = listOf("b", "a", "c")
        assertEquals(listOf("b", "a", "c", "d", "e"), ToolOrder.reconcile(default, saved))
    }

    @Test fun `result is always a permutation of default`() {
        val saved = listOf("z", "c", "c", "a", "y")
        val result = ToolOrder.reconcile(default, saved)
        assertEquals(default.toSet(), result.toSet())
        assertEquals(default.size, result.size)
        assertTrue(result.containsAll(default))
    }

    @Test fun `duplicate saved ids collapse to one`() {
        val saved = listOf("a", "a", "b", "b", "c")
        assertEquals(listOf("a", "b", "c", "d", "e"), ToolOrder.reconcile(default, saved))
    }
}
