package com.tadmor.app.gl

import android.content.Context

/**
 * Loads GLSL shader source code from assets/shaders/.
 */
object ShaderSource {

    fun load(context: Context, fileName: String): String =
        context.assets.open("shaders/$fileName").bufferedReader().use { it.readText() }
}
