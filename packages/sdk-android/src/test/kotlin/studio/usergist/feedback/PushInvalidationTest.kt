package studio.usergist.feedback

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import studio.usergist.feedback.api.Consent
import studio.usergist.feedback.internal.ConsentStore
import studio.usergist.feedback.internal.IdentityStore
import studio.usergist.feedback.internal.SecureStore
import studio.usergist.feedback.internal.Storage
import studio.usergist.feedback.internal.transport.ApiClient
import studio.usergist.feedback.internal.transport.RetryPolicy
import studio.usergist.feedback.push.Push

@OptIn(ExperimentalCoroutinesApi::class)
class PushInvalidationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var server: MockWebServer
    private lateinit var secure: SecureStore
    private lateinit var api: ApiClient

    private fun field(name: String) = UserGist::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun <T> ref(name: String) = field(name).get(null) as AtomicReference<T?>

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
        val constructor = SecureStore::class.java.getDeclaredConstructor(SharedPreferences::class.java, SharedPreferences::class.java)
        constructor.isAccessible = true
        secure = constructor.newInstance(preferences(), preferences())
        val storage = Storage.forTest(Files.createTempDirectory("push-invalidation-").toFile())
        secure.write(SecureStore.Key.IDENTITY, """{"anonymousId":"installation-a"}""")
        val identity = IdentityStore(storage, secure, json)
        val consent = ConsentStore(storage, secure, json).apply { set(Consent(push = true)) }
        api = ApiClient(server.url("/").toString().trimEnd('/'), "write-key", json, policy = RetryPolicy(maxAttempts = 1))
        api.setSubjectToken("st_session")
        ref<SecureStore>("secureRef").set(secure)
        ref<IdentityStore>("identityRef").set(identity)
        ref<ConsentStore>("consentRef").set(consent)
        ref<ApiClient>("apiRef").set(api)
        ref<String>("subjectTokenRef").set("st_session")
        (field("initialized").get(null) as AtomicBoolean).set(true)
        (field("resetInProgress").get(null) as AtomicBoolean).set(false)
        field("pushRegistrationKey").set(null, null)
        Push.reset()
    }

    @After fun tearDown() {
        (field("initialized").get(null) as AtomicBoolean).set(false)
        api.cancelAll()
        ref<ApiClient>("apiRef").set(null)
        ref<SecureStore>("secureRef").set(null)
        ref<IdentityStore>("identityRef").set(null)
        ref<ConsentStore>("consentRef").set(null)
        ref<String>("subjectTokenRef").set(null)
        Push.reset()
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test fun invalidation_follows_registration_survives_retry_and_allows_explicit_enable() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"registered":true}}""").setBodyDelay(150, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{}}"""))
        Push.didReceiveFcmToken("device")
        assertEquals("/v1/sdk/push/register-token", server.takeRequest(3, TimeUnit.SECONDS)?.path)
        Push.invalidateDeviceToken("device")
        retryRegistration()
        assertEquals("/v1/sdk/push/invalidate-token", server.takeRequest(3, TimeUnit.SECONDS)?.path)
        withTimeout(3000) {
            while (secure.read(SecureStore.Key.PUSH_TOKEN) != null || field("pushRegistrationKey").get(null) != null) delay(10)
        }
        assertNull(Push.lastToken())
        // Clearing volatile acknowledgement models a new runtime over the same durable store.
        field("pushRegistrationKey").set(null, null)
        retryRegistration()
        assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"registered":true}}"""))
        Push.didReceiveFcmToken("device")
        assertEquals("/v1/sdk/push/register-token", server.takeRequest(3, TimeUnit.SECONDS)?.path)
        withTimeout(3000) { while (field("pushRegistrationKey").get(null) == null) delay(10) }
        assertEquals("device", secure.read(SecureStore.Key.PUSH_TOKEN))
    }

    private fun retryRegistration() {
        UserGist::class.java.getDeclaredMethod("retryPushRegistration").apply { isAccessible = true }.invoke(UserGist)
    }

    private fun preferences(): SharedPreferences {
        val values = java.util.concurrent.ConcurrentHashMap<String, String>()
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1] as String; proxy }
                "remove" -> { values.remove(args!![0] as String); proxy }
                "commit" -> true
                "apply" -> null
                else -> proxy
            }
        }
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                "contains" -> values.containsKey(args!![0] as String)
                "getAll" -> values.toMap()
                else -> null
            }
        } as SharedPreferences
    }
}
