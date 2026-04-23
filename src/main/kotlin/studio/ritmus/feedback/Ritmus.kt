package studio.ritmus.feedback

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import studio.ritmus.feedback.api.Consent
import studio.ritmus.feedback.api.Environment
import studio.ritmus.feedback.api.PromptResponseInfo
import studio.ritmus.feedback.api.PromptTheme
import studio.ritmus.feedback.internal.Config
import studio.ritmus.feedback.internal.ConsentStore
import studio.ritmus.feedback.internal.EventQueue
import studio.ritmus.feedback.internal.Identity
import studio.ritmus.feedback.internal.IdentityStore
import studio.ritmus.feedback.internal.RitmusLogger
import studio.ritmus.feedback.internal.Storage
import studio.ritmus.feedback.internal.lifecycle.AppLifecycleObserver
import studio.ritmus.feedback.internal.model.ArmedTrigger
import studio.ritmus.feedback.internal.model.IngestBatch
import studio.ritmus.feedback.internal.model.IngestEvent
import studio.ritmus.feedback.internal.transport.ApiClient
import studio.ritmus.feedback.internal.transport.Endpoints
import studio.ritmus.feedback.internal.triggers.FrequencyCapStore
import studio.ritmus.feedback.internal.triggers.RulesCache
import studio.ritmus.feedback.internal.triggers.TriggerMatcher
import studio.ritmus.feedback.internal.triggers.UserState
import studio.ritmus.feedback.internal.ui.PromptPresenter
import studio.ritmus.feedback.internal.util.AnyMap
import studio.ritmus.feedback.internal.util.ContextBuilder
import studio.ritmus.feedback.internal.util.DateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The SDK entry point. Matches the public API defined in DEV_PRD §6.
 *
 * Thread-safety: every public method is safe to call from any thread.
 * All heavy work is dispatched to an internal IO coroutine scope so
 * callers never block the UI thread. Cold-start cost is dominated by
 * filesystem reads on the IO dispatcher, not the synchronous part of
 * [initialize].
 */
object Ritmus {

    // ---------------- Callbacks ----------------

    /** Invoked on the main thread when a prompt is shown. */
    @Volatile
    var onPromptShown: ((String) -> Unit)? = null
        set(value) {
            field = value
            presenterRef.get()?.onPromptShown = value
        }

    /** Invoked on the main thread when a response (including dismissal) is produced. */
    @Volatile
    var onResponse: ((PromptResponseInfo) -> Unit)? = null
        set(value) {
            field = value
            presenterRef.get()?.onResponse = value
        }

    /** Stable, workspace-scoped anonymous identifier (UUID). */
    val anonymousId: String
        get() = identityRef.get()?.load()?.anonymousId ?: ""

    // ---------------- Internal state ----------------

    private val initialized: AtomicBoolean = AtomicBoolean(false)
    private val configRef: AtomicReference<Config?> = AtomicReference(null)
    private val appContextRef: AtomicReference<Context?> = AtomicReference(null)
    private val identityRef: AtomicReference<IdentityStore?> = AtomicReference(null)
    private val consentRef: AtomicReference<ConsentStore?> = AtomicReference(null)
    private val queueRef: AtomicReference<EventQueue?> = AtomicReference(null)
    private val rulesRef: AtomicReference<RulesCache?> = AtomicReference(null)
    private val capsRef: AtomicReference<FrequencyCapStore?> = AtomicReference(null)
    private val matcherRef: AtomicReference<TriggerMatcher?> = AtomicReference(null)
    private val apiRef: AtomicReference<ApiClient?> = AtomicReference(null)
    private val presenterRef: AtomicReference<PromptPresenter?> = AtomicReference(null)
    private val lifecycleRef: AtomicReference<AppLifecycleObserver?> = AtomicReference(null)

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var flushJob: Job? = null

    @Volatile
    private var syncJob: Job? = null

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        coerceInputValues = true
    }

    // ---------------- Public API ----------------

    /**
     * Initialises the SDK. Safe to call multiple times with the same
     * [writeKey] (subsequent calls with different keys are ignored with
     * a warning — create a fresh process to switch keys).
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(
        context: Context,
        writeKey: String,
        environment: Environment = Environment.PRODUCTION,
        apiUrl: String? = null,
        debug: Boolean = false,
        flushIntervalMs: Long = 15_000,
        flushBatchSize: Int = 100,
        maxQueueSize: Int = 1_000,
        triggerSyncIntervalMs: Long = 300_000,
    ) {
        runCatching {
            doInitialize(
                context = context,
                writeKey = writeKey,
                environment = environment,
                apiUrl = apiUrl,
                debug = debug,
                flushIntervalMs = flushIntervalMs,
                flushBatchSize = flushBatchSize,
                maxQueueSize = maxQueueSize,
                triggerSyncIntervalMs = triggerSyncIntervalMs,
            )
        }.onFailure { RitmusLogger.e("Ritmus.initialize failed", it) }
    }

    /**
     * Sets or updates the logged-in user. [properties] are merged into
     * the user's property snapshot (server-side).
     */
    @JvmStatic
    @JvmOverloads
    fun identify(userId: String, properties: Map<String, Any?>? = null) {
        if (!initialized.get()) return
        if (userId.isBlank()) {
            RitmusLogger.w("Ritmus.identify: userId is blank")
            return
        }
        val props = AnyMap.toJsonObject(properties)
        val identity = identityRef.get() ?: return
        val updated = identity.update { current ->
            current.withExternalId(userId, props)
        }
        RitmusLogger.d("Ritmus.identify: externalId=${updated.externalId}")
        enqueueIdentifyServer(updated, props)
    }

    /**
     * Records an event. Safe to call before consent — the event is
     * queued locally and transmitted once consent is granted.
     */
    @JvmStatic
    @JvmOverloads
    fun track(eventName: String, properties: Map<String, Any?>? = null) {
        if (!initialized.get()) return
        if (eventName.isBlank()) {
            RitmusLogger.w("Ritmus.track: eventName is blank")
            return
        }
        val identity = identityRef.get()?.load() ?: return
        val event = IngestEvent(
            name = eventName,
            timestamp = DateTime.nowIso(),
            anonymousId = identity.anonymousId,
            externalId = identity.externalId,
            properties = AnyMap.toJsonObject(properties),
            sessionId = null,
            sdkVersion = BuildConfig.SDK_VERSION,
            appVersion = null,
            platform = Config.SDK_PLATFORM,
        )
        scope.launch {
            try {
                val queue = queueRef.get() ?: return@launch
                queue.enqueue(event)
                RitmusLogger.d("Ritmus.track enqueued '$eventName' (queue=${queue.size()})")
                updateLocalUserStateForEvent(event)
                maybeFireTriggerFor(event)
                if (shouldFlushNow()) flushAsync()
            } catch (e: Throwable) {
                RitmusLogger.w("Ritmus.track dispatch failed", e)
            }
        }
    }

    /**
     * Updates consent. Transport is hard-blocked until
     * [Consent.feedback] is `true`. On grant we immediately flush any
     * backlog.
     */
    @JvmStatic
    fun setConsent(purposes: Consent) {
        if (!initialized.get()) return
        val store = consentRef.get() ?: return
        val previous = store.get()
        store.set(purposes)
        RitmusLogger.d("Ritmus.setConsent: $purposes")
        enqueueConsentServer(purposes)
        if (!previous.allowsTransport && purposes.allowsTransport) {
            flushAsync()
        }
    }

    /** Clears identity and queued events. Keeps consent. */
    @JvmStatic
    fun reset() {
        if (!initialized.get()) return
        scope.launch {
            try {
                identityRef.get()?.reset()
                queueRef.get()?.clear()
                capsRef.get()?.clear()
                rulesRef.get()?.clear()
                RitmusLogger.d("Ritmus.reset complete")
            } catch (e: Throwable) {
                RitmusLogger.w("Ritmus.reset failed", e)
            }
        }
    }

    /** Applies host-app theme overrides used when rendering prompts. */
    @JvmStatic
    fun setThemeOverrides(theme: PromptTheme) {
        presenterRef.get()?.setThemeOverrides(theme)
    }

    /** Eagerly flushes any queued events. No-op if consent blocks transport. */
    @JvmStatic
    fun flush() {
        if (!initialized.get()) return
        flushAsync()
    }

    /** Toggles debug logging at runtime. */
    @JvmStatic
    fun setDebug(enabled: Boolean) {
        RitmusLogger.setDebug(enabled)
    }

    // ---------------- Push ----------------

    /** Internal: register an FCM token with the control plane. */
    @JvmStatic
    internal fun registerPushToken(token: String) {
        if (!initialized.get()) return
        val api = apiRef.get() ?: return
        val consent = consentRef.get()?.get() ?: return
        if (!consent.allowsPush) {
            RitmusLogger.d("push register skipped: consent not granted")
            return
        }
        val identity = identityRef.get()?.load() ?: return
        val context = appContextRef.get()
        val appVersion = try {
            context?.packageManager?.getPackageInfo(context.packageName, 0)?.versionName
        } catch (_: Throwable) {
            null
        }
        scope.launch {
            try {
                val payload = SdkRegisterTokenPayload(
                    anonymousId = identity.anonymousId,
                    externalId = identity.externalId,
                    token = token,
                    platform = "android",
                    environment = "production",
                    language = java.util.Locale.getDefault().toLanguageTag(),
                    timezone = java.util.TimeZone.getDefault().id,
                    appVersion = appVersion,
                    sdkVersion = Config.SDK_VERSION,
                    optIn = true,
                )
                api.postJson(
                    path = Endpoints.PUSH_REGISTER_TOKEN,
                    body = payload,
                    serializer = SdkRegisterTokenPayload.serializer(),
                )
            } catch (e: Throwable) {
                RitmusLogger.w("Ritmus.registerPushToken failed", e)
            }
        }
    }

    /** Internal: emit a $push_* analytics event carrying the payload envelope. */
    @JvmStatic
    internal fun trackPushEvent(
        eventName: String,
        msg: studio.ritmus.feedback.push.RitmusPushMessage,
        actionButton: String?,
    ) {
        val props = mutableMapOf<String, Any?>()
        msg.campaignId?.let { props["campaign_id"] = it }
        msg.variantId?.let { props["variant_id"] = it }
        msg.deliveryId?.let { props["delivery_id"] = it }
        msg.language?.let { props["language"] = it }
        actionButton?.let { props["action_button"] = it }
        track(eventName, props)
    }

    // ---------------- Internals ----------------

    private fun doInitialize(
        context: Context,
        writeKey: String,
        environment: Environment,
        apiUrl: String?,
        debug: Boolean,
        flushIntervalMs: Long,
        flushBatchSize: Int,
        maxQueueSize: Int,
        triggerSyncIntervalMs: Long,
    ) {
        val appCtx = context.applicationContext
        val resolvedUrl = apiUrl?.takeIf { it.isNotBlank() } ?: environment.defaultApiUrl
        val config = Config(
            writeKey = writeKey,
            environment = environment,
            apiUrl = resolvedUrl,
            debug = debug,
            flushIntervalMs = flushIntervalMs.coerceAtLeast(1_000),
            flushBatchSize = flushBatchSize.coerceAtLeast(1),
            maxQueueSize = maxQueueSize.coerceAtLeast(1),
            triggerSyncIntervalMs = triggerSyncIntervalMs.coerceAtLeast(30_000),
        )

        val existing = configRef.get()
        if (initialized.get() && existing?.writeKey == writeKey) {
            // Idempotent reconfiguration — only runtime toggles change.
            RitmusLogger.setDebug(debug)
            configRef.set(config)
            return
        }
        if (initialized.get() && existing != null && existing.writeKey != writeKey) {
            RitmusLogger.w("Ritmus.initialize called with a different writeKey; ignoring")
            return
        }

        RitmusLogger.setDebug(debug)
        val storage = Storage(appCtx, writeKey)
        val identityStore = IdentityStore(storage, json)
        val consentStore = ConsentStore(storage, json)
        val eventQueue = EventQueue(storage, json, config.maxQueueSize)
        val rulesCache = RulesCache(storage, json)
        val capStore = FrequencyCapStore(storage, json)
        val matcher = TriggerMatcher(rulesCache, capStore)
        val apiClient = ApiClient(config.apiUrl, writeKey, json)

        configRef.set(config)
        appContextRef.set(appCtx)
        identityRef.set(identityStore)
        consentRef.set(consentStore)
        queueRef.set(eventQueue)
        rulesRef.set(rulesCache)
        capsRef.set(capStore)
        matcherRef.set(matcher)
        apiRef.set(apiClient)

        val app = appCtx as? Application
        if (app != null) {
            val lifecycle = AppLifecycleObserver(
                application = app,
                onForeground = ::onForeground,
                onBackground = ::onBackground,
            ).also { it.start() }
            lifecycleRef.set(lifecycle)
            val presenter = PromptPresenter(lifecycle).apply {
                onPromptShown = this@Ritmus.onPromptShown
                onResponse = this@Ritmus.onResponse
            }
            presenterRef.set(presenter)
        } else {
            RitmusLogger.w("Ritmus.initialize: context is not an Application; prompts will not render")
        }

        // Warm the stores + bootstrap rules cache off the hot path.
        scope.launch {
            try {
                identityStore.load()
                rulesCache.bootstrapFromDisk()
                RitmusLogger.d("Ritmus.initialize: background warmup complete")
            } catch (e: Throwable) {
                RitmusLogger.w("Ritmus.initialize warmup failed", e)
            }
        }

        initialized.set(true)

        startFlushLoop()
        startTriggerSyncLoop()
    }

    private fun startFlushLoop() {
        flushJob?.cancel()
        val config = configRef.get() ?: return
        flushJob = scope.launch {
            while (isActive) {
                try {
                    delay(config.flushIntervalMs)
                    flushOnce()
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    RitmusLogger.w("Ritmus flush loop error", e)
                }
            }
        }
    }

    private fun startTriggerSyncLoop() {
        syncJob?.cancel()
        val config = configRef.get() ?: return
        syncJob = scope.launch {
            // Initial refresh quickly after init.
            try {
                delay(1_000)
                refreshArmedTriggers()
            } catch (_: Throwable) { /* loop below handles recovery */ }
            while (isActive) {
                try {
                    delay(config.triggerSyncIntervalMs)
                    refreshArmedTriggers()
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    RitmusLogger.w("Ritmus sync loop error", e)
                }
            }
        }
    }

    private fun onForeground() {
        RitmusLogger.d("Ritmus: app entered foreground — refreshing armed triggers + flushing")
        flushAsync()
        scope.launch { refreshArmedTriggers() }
    }

    private fun onBackground() {
        RitmusLogger.d("Ritmus: app entered background — flushing")
        flushAsync()
    }

    private fun flushAsync() {
        scope.launch { flushOnce() }
    }

    private suspend fun flushOnce() {
        val consent = consentRef.get() ?: return
        if (!consent.allowsTransport()) {
            RitmusLogger.d("Ritmus.flush skipped — consent.feedback not granted")
            return
        }
        val queue = queueRef.get() ?: return
        val api = apiRef.get() ?: return
        val config = configRef.get() ?: return
        val identity = identityRef.get()?.load() ?: return
        val appCtx = appContextRef.get() ?: return
        if (queue.size() == 0) return
        while (queue.size() > 0) {
            val batch = queue.peekBatch(config.flushBatchSize)
            if (batch.events.isEmpty()) break
            val context = ContextBuilder.build(
                context = appCtx,
                anonymousId = identity.anonymousId,
                externalId = identity.externalId,
            )
            val success = api.postJson(
                path = Endpoints.INGEST,
                body = IngestBatch(events = batch.events, context = context),
                serializer = IngestBatch.serializer(),
            )
            if (success) {
                batch.consume()
                RitmusLogger.d("Ritmus.flush sent ${batch.events.size} events")
            } else {
                RitmusLogger.d("Ritmus.flush failed for ${batch.events.size} events; will retry")
                break
            }
        }
    }

    private suspend fun refreshArmedTriggers() {
        val consent = consentRef.get() ?: return
        if (!consent.allowsTransport()) {
            RitmusLogger.d("Ritmus.refreshArmedTriggers skipped — consent.feedback not granted")
            return
        }
        val rules = rulesRef.get() ?: return
        val api = apiRef.get() ?: return
        val identity = identityRef.get()?.load() ?: return
        rules.refresh(api, identity.anonymousId, identity.externalId)
    }

    private fun maybeFireTriggerFor(event: IngestEvent) {
        val matcher = matcherRef.get() ?: return
        val consent = consentRef.get() ?: return
        if (!consent.get().allowsTransport) {
            RitmusLogger.d("Ritmus: trigger eval skipped for '${event.name}' — consent")
            return
        }
        val user = buildUserState()
        val armed = matcher.match(event.name, user) ?: return
        present(armed)
    }

    private fun present(trigger: ArmedTrigger) {
        val presenter = presenterRef.get() ?: return
        val caps = capsRef.get()
        scope.launch {
            try {
                val shown = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    presenter.present(trigger.prompt)
                }
                if (shown) {
                    caps?.recordShown(trigger.promptId)
                }
            } catch (e: Throwable) {
                RitmusLogger.w("Ritmus.present failed", e)
            }
        }
    }

    // ---------------- Local user-state model ----------------

    private val userStateLock = Any()

    @Volatile
    private var lastEventAt: MutableMap<String, Long> = mutableMapOf()

    @Volatile
    private var eventCounts: MutableMap<String, MutableMap<Int, Int>> = mutableMapOf()

    private fun updateLocalUserStateForEvent(event: IngestEvent) {
        synchronized(userStateLock) {
            lastEventAt[event.name] = DateTime.parseIso(event.timestamp) ?: System.currentTimeMillis()
            val buckets = eventCounts.getOrPut(event.name) { mutableMapOf() }
            // Increment every bucket we care about. Without historical
            // timestamps we over-count older windows, but counts can only
            // be equal or higher than the true value, which is strictly
            // safer for "fire on at least N" predicates (server
            // re-verifies).
            for (window in trackedWindowsDays) {
                buckets[window] = (buckets[window] ?: 0) + 1
            }
        }
    }

    private fun buildUserState(): UserState {
        val identity = identityRef.get()?.load()
        val props = HashMap<String, Any?>()
        identity?.userProperties?.let { jo ->
            AnyMap.fromJsonObject(jo)?.let { props.putAll(it) }
        }
        identity?.externalId?.let { props["external_id"] = it }
        synchronized(userStateLock) {
            return UserState(
                properties = props,
                eventCounts = eventCounts.mapValues { it.value.toMap() }.toMap(),
                lastEventAt = lastEventAt.toMap(),
            )
        }
    }

    private fun shouldFlushNow(): Boolean {
        val queue = queueRef.get() ?: return false
        val config = configRef.get() ?: return false
        return queue.size() >= config.flushBatchSize
    }

    private fun enqueueIdentifyServer(identity: Identity, properties: JsonObject?) {
        val api = apiRef.get() ?: return
        val consent = consentRef.get() ?: return
        if (!consent.allowsTransport()) return
        scope.launch {
            try {
                val payload = SdkIdentifyPayload(
                    anonymousId = identity.anonymousId,
                    externalId = identity.externalId ?: return@launch,
                    properties = properties,
                )
                api.postJson(
                    path = Endpoints.IDENTIFY,
                    body = payload,
                    serializer = SdkIdentifyPayload.serializer(),
                )
            } catch (e: Throwable) {
                RitmusLogger.w("Ritmus.identify send failed", e)
            }
        }
    }

    private fun enqueueConsentServer(consent: Consent) {
        val api = apiRef.get() ?: return
        val identity = identityRef.get()?.load() ?: return
        if (consent.feedback != true) return
        scope.launch {
            try {
                val payload = SdkConsentPayload(
                    anonymousId = identity.anonymousId,
                    externalId = identity.externalId,
                    purposes = SdkConsentPayload.Purposes(
                        analytics = consent.analytics,
                        feedback = consent.feedback,
                        push = consent.push,
                    ),
                )
                api.postJson(
                    path = Endpoints.CONSENT,
                    body = payload,
                    serializer = SdkConsentPayload.serializer(),
                )
            } catch (e: Throwable) {
                RitmusLogger.w("Ritmus.consent send failed", e)
            }
        }
    }

    // ---------------- Wire payloads ----------------

    @kotlinx.serialization.Serializable
    internal data class SdkIdentifyPayload(
        val anonymousId: String,
        val externalId: String,
        val properties: JsonObject? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class SdkConsentPayload(
        val anonymousId: String,
        val externalId: String? = null,
        val purposes: Purposes,
    ) {
        @kotlinx.serialization.Serializable
        internal data class Purposes(
            val analytics: Boolean? = null,
            val feedback: Boolean? = null,
            val push: Boolean? = null,
        )
    }

    @kotlinx.serialization.Serializable
    internal data class SdkRegisterTokenPayload(
        val anonymousId: String,
        val externalId: String? = null,
        val token: String,
        val platform: String,
        val environment: String,
        val language: String? = null,
        val timezone: String? = null,
        val appVersion: String? = null,
        val sdkVersion: String? = null,
        val optIn: Boolean = true,
    )

    private val trackedWindowsDays: IntArray = intArrayOf(1, 7, 14, 30, 90)
}
