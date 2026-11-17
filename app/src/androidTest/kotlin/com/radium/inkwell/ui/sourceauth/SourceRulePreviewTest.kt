package com.radium.inkwell.ui.sourceauth

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.radium.inkwell.MainActivity
import com.radium.inkwell.ui.components.SettingBlock
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.sourcedetail.SourceRulePreview
import com.radium.inkwell.ui.theme.InkwellTheme
import org.junit.Assert.*
import org.junit.Test
import java.util.regex.Pattern

class SourceRulePreviewTest {
    @Test fun 长规则默认不展示全文且可按段查看() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val activity = inst.startActivitySync(Intent(inst.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val rule = "规则开头\n" + "x".repeat(50000) + "规则末尾"
        try {
            inst.runOnMainSync { activity.setContent { InkwellTheme { Column { SettingGroup { SettingBlock { SourceRulePreview(rule) } } } } } }
            val device = UiDevice.getInstance(inst)
            val show = device.wait(Until.findObject(By.text("查看规则原文")), 10000)
            assertNotNull(show)
            assertFalse(device.hasObject(By.textContains("规则开头")))
            show.click()
            assertTrue(device.wait(Until.hasObject(By.text(Pattern.compile("1 / [0-9]+"))), 10000))
            assertTrue(device.hasObject(By.textContains("规则开头")))
            assertFalse(device.hasObject(By.textContains("规则末尾")))
            device.findObject(By.text("下一段")).click()
            assertTrue(device.wait(Until.hasObject(By.text(Pattern.compile("2 / [0-9]+"))), 10000))
        } finally { inst.runOnMainSync { activity.finish() } }
    }
}
