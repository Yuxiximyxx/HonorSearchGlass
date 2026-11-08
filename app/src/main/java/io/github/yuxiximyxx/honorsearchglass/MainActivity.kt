package io.github.yuxiximyxx.honorsearchglass

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** 极简状态页：说明模块用途与生效条件。模块本身没有设置项。 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "荣耀搜索毛玻璃"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "\n把荣耀搜索底部搜索框的底板改成毛玻璃，与页面自身的模糊背景统一。\n\n" +
                "使用方法：在 LSPosed 中启用本模块，作用域勾选「荣耀搜索」" +
                "（com.hihonor.search），然后强行停止并重新打开荣耀搜索。\n\n" +
                "没有设置项；不想要了直接在 LSPosed 里关掉模块即可。"
            textSize = 15f
        })
        setContentView(root)
    }
}
