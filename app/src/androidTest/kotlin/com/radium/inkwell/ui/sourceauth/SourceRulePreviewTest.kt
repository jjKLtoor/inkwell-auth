package com.radium.inkwell.ui.sourceauth

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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

class SourceRulePreviewTest {
    @Test fun 长规则默认不展示全文且展开直接显示全文() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val activity = inst.startActivitySync(Intent(inst.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val rule = "规则开头\n" + "x".repeat(50000) + "规则末尾"
        try {
            inst.runOnMainSync { activity.setContent { InkwellTheme { Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { SettingGroup { SettingBlock { SourceRulePreview(rule) } } } } } }
            val device = UiDevice.getInstance(inst)
            val show = device.wait(Until.findObject(By.text("查看规则原文")), 10000)
            assertNotNull(show)
            assertFalse(device.hasObject(By.textContains("规则开头")))
            show.click()
            assertTrue(device.wait(Until.hasObject(By.textContains("规则开头")), 10000))
            assertTrue(device.wait(Until.hasObject(By.textContains("规则末尾")), 10000))
            assertFalse(device.hasObject(By.text("下一段")))
        } finally { inst.runOnMainSync { activity.finish() } }
    }
}
