package com.misterdownloadmanager.mobile

import android.app.Application

class MdmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 最先安装崩溃捕获，保证任何初始化异常都能记录并复制到剪切板
        CrashHandler.install(this)
        Repo.init(this)
    }
}
