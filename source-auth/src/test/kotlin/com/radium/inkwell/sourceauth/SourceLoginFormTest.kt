package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.BookSourceRule
import kotlin.test.*

class SourceLoginFormTest {
    @Test fun 静态非严格JSON表单支持按钮输入与文字且样式不进入应用主题() {
        val source = BookSourceRule("https://example.com", loginUi = """[{name:'账号',type:'edit'},{name:'密码',type:'password'},{name:'登录',type:'button',action:'go()',style:{width:999}},{name:'说明',type:'text'}]""", loginUrl = "function login(result){return true}")
        val form = parseSourceLoginForm(source)!!
        assertEquals(listOf("edit", "password", "button", "text"), form.controls.map { it.type })
        assertEquals("go()", form.controls[2].action)
        assertTrue(form.hasSubmit)
    }
    @Test fun 动态表达式和不支持控件明确拒绝且不执行() {
        for (ui in listOf("[{name:(function(){throw 'executed'})(),type:'edit'}]", "[{name:'x',type:'select'}]", "[{name:'x',type:'button',action:go()}]")) {
            assertFails { parseSourceLoginForm(BookSourceRule("https://example.com", loginUi = ui)) }
        }
    }
    @Test fun 网页地址不误作脚本且未声明登录函数不显示提交() {
        val form = parseSourceLoginForm(BookSourceRule("https://example.com", loginUrl = "https://example.com/login", loginUi = "[{name:'说明',type:'text'}]"))!!
        assertEquals("", form.script)
        assertFalse(form.hasSubmit)
    }
}
