package io.github.yuxiximyxx.honorsearchglass.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 入口：只处理荣耀搜索主进程（com.hihonor.search）。
 * 子进程（:push 等）不装 hook，避免误触与日志刷屏。
 * 任何异常都在这里吞掉——模块出错绝不能拖垮目标进程。
 */
class HookEntry : XposedModule() {

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        log(Log.INFO, TAG, "ENTRY pkg=${param.packageName} first=${param.isFirstPackage}")

        val processName = runCatching {
            val appClass = Class.forName("android.app.Application")
            appClass.getMethod("getProcessName").invoke(null) as? String
        }.getOrNull()
        log(Log.INFO, TAG, "process=$processName")
        if (processName != TARGET_PACKAGE) {
            log(Log.INFO, TAG, "非主进程，跳过")
            return
        }

        try {
            SearchGlassHook.install(this, param.classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "install 失败（已吞掉，荣耀搜索照常启动）", t)
        }
    }

    companion object {
        const val TAG = "HonorSearchGlass"
        const val TARGET_PACKAGE = "com.hihonor.search"
    }
}
