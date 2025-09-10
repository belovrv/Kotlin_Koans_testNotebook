import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.Runner
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunNotifier
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.*


val mdHeader = "#%% md"
val codeHeader = "#%%"

// Утилита: достаём «настоящую» причину из цепочки исключений
fun unwrapThrowable(t: Throwable): Throwable {
    var cur: Throwable = t
    if (cur is ExecutionException && cur.cause != null) cur = cur.cause!!
    if (cur is InvocationTargetException && cur.targetException != null) cur = cur.targetException
    return cur
}

/** Runner, который запускает @Test на ПЕРЕДАННОМ экземпляре и корректно разворачивает исключения */
class InstanceRunner(private val instance: Any) : Runner() {
    private val methods: List<Method> =
        instance::class.java.methods.filter { it.isAnnotationPresent(Test::class.java) }

    private val suite: Description = Description.createSuiteDescription(instance::class.java).apply {
        methods.forEach { addChild(Description.createTestDescription(instance::class.java, it.name)) }
    }

    override fun getDescription(): Description = suite

    override fun run(notifier: RunNotifier) {
        val pool = Executors.newSingleThreadExecutor()
        try {
            for (m in methods) {
                val ann = m.getAnnotation(Test::class.java)
                val desc = Description.createTestDescription(instance::class.java, m.name)
                notifier.fireTestStarted(desc)
                try {
                    val fut = pool.submit(Callable { m.invoke(instance) })
                    if (ann.timeout > 0) {
                        try {
                            fut.get(ann.timeout, TimeUnit.MILLISECONDS)
                        } catch (e: TimeoutException) {
                            fut.cancel(true)
                            throw AssertionError("test timed out after ${ann.timeout} ms")
                        }
                    } else {
                        fut.get()
                    }
                    // Ожидали исключение, но не получили
                    if (ann.expected != Test.None::class) {
                        notifier.fireTestFailure(
                            Failure(desc, AssertionError("Expected ${ann.expected.java.name} to be thrown"))
                        )
                    }
                } catch (raw: Throwable) {
                    val cause = unwrapThrowable(raw)
                    val expected = ann.expected
                    if (expected != Test.None::class && expected.java.isInstance(cause)) {
                        // Ожидаемое исключение — ок
                    } else {
                        cause.printStackTrace() // полезно в ноутбуке
                        notifier.fireTestFailure(Failure(desc, cause))
                    }
                } finally {
                    notifier.fireTestFinished(desc)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }
}