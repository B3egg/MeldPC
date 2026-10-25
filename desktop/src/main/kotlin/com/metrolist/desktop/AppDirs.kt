package com.metrolist.desktop

import java.io.File

object AppDirs {
    val data: File by lazy {
        val base =
            System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                ?: System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: File(System.getProperty("user.home"), ".config").path
        File(base, "Meld").apply { mkdirs() }
    }

    fun file(name: String): File = File(data, name)
}
