package com.radium.inkwell.data.prefs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchPriorityPreferencesDeviceTest {
    /** 用独立的调试应用分两次启动测试，验证读取的是磁盘配置。 */
    @Test fun 标签及开关可恢复并取消() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val phase = InstrumentationRegistry.getArguments().getString("priorityPhase")
        assumeTrue(context.packageName.endsWith(".validation") && phase in setOf("write", "read"))
        val prefs = AppPrefs(context)
        val source = "https://priority.example.test"
        val book = "priority-fixture-book"
        if (phase == "write") {
            context.noBackupFilesDir.resolve("priority-test-process").writeText(android.os.Process.myPid().toString())
            prefs.setSourceSearchPriority(source, true)
            prefs.setBookSearchPriority(book, true)
            prefs.setSearchPrioritiesEnabled(false)
        } else {
            assertTrue(context.noBackupFilesDir.resolve("priority-test-process").readText() != android.os.Process.myPid().toString())
            assertEquals(SearchPriorityPreferences(false, setOf(source), setOf(book)), prefs.searchPriorities.first())
            prefs.setBookSearchPriority(book, false)
            prefs.setSourceSearchPriority(source, false)
            prefs.setSearchPrioritiesEnabled(true)
            assertEquals(SearchPriorityPreferences(), prefs.searchPriorities.first())
        }
    }
}
