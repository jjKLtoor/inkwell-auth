# Rhino 按公开名称反射调用规则桥；规则随 core JAR 交付，避免每次合并都在 app 重复维护。
-keep class com.radium.inkwell.core.source.js.** { public protected *; }
