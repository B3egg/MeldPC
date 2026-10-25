package timber.log

/**
 * Minimal stand-in for Android's Timber so the shared Android sources compile on the JVM.
 * Debug output is only printed when the app is started with -Dmeld.debug=true.
 */
object Timber {
    private val debug = System.getProperty("meld.debug") == "true"

    class Tree internal constructor(private val tag: String?) {
        fun v(message: String, vararg args: Any?) = log('V', null, message, args)
        fun d(message: String, vararg args: Any?) = log('D', null, message, args)
        fun i(message: String, vararg args: Any?) = log('I', null, message, args)
        fun w(message: String, vararg args: Any?) = log('W', null, message, args)
        fun w(t: Throwable?, message: String, vararg args: Any?) = log('W', t, message, args)
        fun w(t: Throwable?) = log('W', t, "", emptyArray())
        fun e(message: String, vararg args: Any?) = log('E', null, message, args)
        fun e(t: Throwable?, message: String, vararg args: Any?) = log('E', t, message, args)
        fun e(t: Throwable?) = log('E', t, "", emptyArray())

        private fun log(level: Char, t: Throwable?, message: String, args: Array<out Any?>) {
            if (!debug && level != 'W' && level != 'E') return
            val text = if (args.isEmpty()) message else runCatching { message.format(*args) }.getOrDefault(message)
            System.err.println("$level/${tag ?: "Meld"}: $text")
            if (t != null && debug) t.printStackTrace()
        }
    }

    private val root = Tree(null)

    fun tag(tag: String): Tree = Tree(tag)

    fun v(message: String, vararg args: Any?) = root.v(message, *args)
    fun d(message: String, vararg args: Any?) = root.d(message, *args)
    fun i(message: String, vararg args: Any?) = root.i(message, *args)
    fun w(message: String, vararg args: Any?) = root.w(message, *args)
    fun w(t: Throwable?, message: String, vararg args: Any?) = root.w(t, message, *args)
    fun w(t: Throwable?) = root.w(t)
    fun e(message: String, vararg args: Any?) = root.e(message, *args)
    fun e(t: Throwable?, message: String, vararg args: Any?) = root.e(t, message, *args)
    fun e(t: Throwable?) = root.e(t)
}
