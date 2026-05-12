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

    // ---------------- Surveys ----------------

    @Volatile
    var surveyHandlers: studio.ritmus.feedback.api.SurveyHandlers =
        studio.ritmus.feedback.api.SurveyHandlers()

    /**
     * Fetches surveys currently open to this user. v1: consent-gated no-op
     * scaffold — the full transport path ships with the native multi-step
     * renderer. Returns an empty list today.
     */
    fun getAvailableSurveys(
        callback: (List<studio.ritmus.feedback.api.SurveySummary>) -> Unit,
    ) {
        if (!initialized.get()) {
            callback(emptyList())
            return
        }
        val consent = consentRef.get()?.get()
        if (consent?.allowsSurvey != true) {
            callback(emptyList())
            return
        }
        RitmusLogger.d("getAvailableSurveys: survey renderer not yet available on Android")
        callback(emptyList())
    }

    /**
     * Requests that the host app render the specified survey. v1 surface: the
     * SDK does not ship a native multi-step renderer yet; the host app is
     * expected to use [surveyHandlers.onShow] to open its own UI.
     */
    fun openSurvey(surveyId: String, language: String? = null) {
        if (!initialized.get()) return
        val consent = consentRef.get()?.get()
        if (consent?.allowsSurvey != true) return
        surveyHandlers.onShow?.invoke(surveyId)
    }

    /**
     * Handles a Ritmus survey share link. Returns true when the URL is a
     * recognized Ritmus survey link.
     */
    fun handleSurveyDeepLink(uri: android.net.Uri): Boolean {
        if (!initialized.get()) return false
        val pathToken = uri.pathSegments.takeIf { it.size >= 2 && it[0] == "s" }?.get(1)
        val queryToken = uri.getQueryParameter("survey")
        val token = pathToken ?: queryToken ?: return false
        if (token.isEmpty()) return false
        RitmusLogger.d("survey.deep-link token=${token.take(8)}…")
        return true
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

    /** Internal: re-bind the most-recently-registered token to a new identified user. */
    @JvmStatic
    internal fun rebindPushToken(externalId: String, token: String) {
        if (!initialized.get() || externalId.isBlank() || token.isBlank()) return
        val api = apiRef.get() ?: return
        val identity = identityRef.get()?.load() ?: return
        scope.launch {
            try {
                val payload = SdkRebindPayload(
                    anonymousId = identity.anonymousId,
                    externalId = externalId,
                    token = token,
                )
                api.postJson(
                    path = Endpoints.PUSH_REBIND,
                    body = payload,
                    serializer = SdkRebindPayload.serializer(),
                )
            } catch (e: Throwable) {
                RitmusLogger.w("Ritmus.rebindPushToken failed", e)
            }
        }
    }

    /** Internal: forward applicationDidBecomeActive to the reachability worker. */
    @JvmStatic
    internal fun reportPushAppOpen() {
        if (!initialized.get()) return
        val api = apiRef.get() ?: return
        val identity = identityRef.get()?.load() ?: return
        scope.launch {
            try {
                val payload = SdkAppOpenPayload(
                    anonymousId = identity.anonymousId,
                    occurredAt = java.time.OffsetDateTime.now().toString(),
                )
                api.postJson(
                    path = Endpoints.PUSH_APP_OPEN,
                    body = payload,
                    serializer = SdkAppOpenPayload.serializer(),
                )
            } catch (e: Throwable) {
                RitmusLogger.w("Ritmus.reportPushAppOpen failed", e)
            }
        }
    }

    /** Internal: emit delivered/displayed/dismissed beacon to the server. */
    @JvmStatic
    internal fun pushBeacon(
        kind: String,
        deliveryId: String,
        actionButton: String? = null,
    ) {
        if (!initialized.get() || deliveryId.isBlank()) return
        val api = apiRef.get() ?: return
        val path = when (kind) {
            "delivered" -> Endpoints.PUSH_DELIVERED
            "displayed" -> Endpoints.PUSH_DISPLAYED
            "dismissed" -> Endpoints.PUSH_DISMISSED
            else -> return
        }
        scope.launch {
            try {
                val payload = SdkBeaconPayload(
                    deliveryId = deliveryId,
                    occurredAt = java.time.OffsetDateTime.now().toString(),
                    actionButton = actionButton,
                )
                api.postJson(
                    path = path,
                    body = payload,
                    serializer = SdkBeaconPayload.serializer(),
                )
            } catch (e: Throwable) {
                RitmusLogger.w("Ritmus.pushBeacon $kind failed", e)
            }
        }
    }

    /** Internal: ack a silent reachability ping. */
    @JvmStatic
    internal fun ackSilentPush(pingId: String) {
        if (!initialized.get() || pingId.isBlank()) return
        val api = apiRef.get() ?: return
        val identity = identityRef.get()?.load() ?: return
        scope.launch {
            try {
                val payload = SdkSilentAckPayload(
                    pingId = pingId,
                    anonymousId = identity.anonymousId,
                    receivedAt = java.time.OffsetDateTime.now().toString(),
                )
                api.postJson(
                    path = Endpoints.PUSH_SILENT_ACK,
                    body = payload,
                    serializer = SdkSilentAckPayload.serializer(),
                )
            } catch (e: Throwable) {
                RitmusLogger.w("Ritmus.ackSilentPush failed", e)
            }
        }
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
        val secureStore = SecureStore.create(appCtx, writeKey)
        val identityStore = IdentityStore(storage, secureStore, json)
        val consentStore = ConsentStore(storage, secureStore, json)
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

    @kotlinx.serialization.Serializable
    internal data class SdkRebindPayload(
        val anonymousId: String,
        val externalId: String,
        val token: String,
    )

    @kotlinx.serialization.Serializable
    internal data class SdkAppOpenPayload(
        val anonymousId: String,
        val occurredAt: String? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class SdkBeaconPayload(
        val deliveryId: String,
        val occurredAt: String? = null,
        val actionButton: String? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class SdkSilentAckPayload(
        val pingId: String,
        val anonymousId: String,
        val receivedAt: String? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class SdkChannelSubscriptionPayload(
        val anonymousId: String,
        val channelId: String,
        val subscribed: Boolean,
    )

    private val trackedWindowsDays: IntArray = intArrayOf(1, 7, 14, 30, 90)

    // ---------------- Feature Requests (5th pillar) ----------------
    // STATUS: API surface declared; HTTP wiring + UI Fragments tracked in
    // PARITY.md. The methods below are deliberately no-ops or scaffolds —
    // host apps can compile against them today and the runtime fills in
    // when the implementation lands in a follow-up PR.

    private var requestsHandlers: studio.ritmus.feedback.api.RequestsHandlers? = null
    private val requestsCache = studio.ritmus.feedback.internal.requests.RequestsCache()

    private fun requestsApi(): studio.ritmus.feedback.internal.requests.RequestsApi? {
        val api = apiRef.get() ?: return null
        return studio.ritmus.feedback.internal.requests.RequestsApi(api, json)
    }

    /** Open the SDK-provided requests board UI. */
    fun openRequestsBoard() {
        if (!initialized.get()) return
        val ctx = appContextRef.get() ?: return
        studio.ritmus.feedback.internal.ui.requests.RequestsBoardLauncher.openBoard(ctx)
    }

    /** Open the detail view for a specific request. */
    fun openRequestDetail(requestId: String) {
        if (!initialized.get()) return
        val ctx = appContextRef.get() ?: return
        studio.ritmus.feedback.internal.ui.requests.RequestsBoardLauncher
            .openDetail(ctx, requestId)
    }

    /**
     * Submit a new request. Validates client-side per spec §7
     * (title ≤120, description ≤1500); server enforces the same.
     */
    fun submitRequest(
        title: String,
        description: String,
        callback: (Throwable?, studio.ritmus.feedback.api.FeatureRequest?) -> Unit,
    ) {
        if (title.isEmpty() || title.length > 120) {
            callback(IllegalArgumentException("title required, max 120 chars"), null)
            return
        }
        if (description.isEmpty() || description.length > 1500) {
            callback(IllegalArgumentException("description required, max 1500 chars"), null)
            return
        }
        val api = requestsApi() ?: return callback(
            IllegalStateException("SDK not initialized"), null,
        )
        val identity = identityRef.get()?.load() ?: return callback(
            IllegalStateException("identity not hydrated"), null,
        )
        scope.launch {
            val result = api.submit(
                anonymousId = identity.anonymousId,
                externalId = identity.externalId,
                title = title,
                description = description,
            )
            if (result == null) {
                callback(IllegalStateException("submit failed"), null)
            } else {
                requestsCache.upsert(result)
                requestsHandlers?.onSubmit(result)
                callback(null, result)
            }
        }
    }

    /** Fetch a page of requests. */
    fun getRequests(
        options: studio.ritmus.feedback.api.GetRequestsOptions =
            studio.ritmus.feedback.api.GetRequestsOptions(),
        callback: (Throwable?, studio.ritmus.feedback.api.GetRequestsResult?) -> Unit,
    ) {
        val api = requestsApi() ?: return callback(
            IllegalStateException("SDK not initialized"), null,
        )
        val identity = identityRef.get()?.load() ?: return callback(
            IllegalStateException("identity not hydrated"), null,
        )
        scope.launch {
            val page = api.list(identity.anonymousId, identity.externalId, options)
            if (page == null) {
                callback(IllegalStateException("getRequests failed"), null)
            } else {
                callback(null, page)
            }
        }
    }

    /** Fetch a single request by id. */
    fun getRequest(
        requestId: String,
        callback: (Throwable?, studio.ritmus.feedback.api.FeatureRequest?) -> Unit,
    ) {
        val api = requestsApi() ?: return callback(IllegalStateException("not init"), null)
        val identity = identityRef.get()?.load() ?: return callback(IllegalStateException("identity"), null)
        scope.launch {
            val req = api.getOne(requestId, identity.anonymousId, identity.externalId)
            if (req == null) callback(IllegalStateException("not found"), null)
            else {
                requestsCache.upsert(req)
                callback(null, req)
            }
        }
    }

    /** Toggle upvote — idempotent, optimistic at the cache layer. */
    fun voteOnRequest(
        requestId: String,
        vote: Boolean,
        callback: ((Throwable?, studio.ritmus.feedback.api.RequestVote?) -> Unit)? = null,
    ) {
        val rollback = requestsCache.applyOptimisticVote(requestId, vote)
        val api = requestsApi() ?: return callback?.invoke(IllegalStateException("not init"), null) ?: Unit
        val identity = identityRef.get()?.load() ?: return callback?.invoke(IllegalStateException("identity"), null) ?: Unit
        scope.launch {
            val outcome = api.vote(requestId, identity.anonymousId, identity.externalId, vote)
            if (outcome == null) {
                rollback()
                callback?.invoke(IllegalStateException("vote failed"), null)
            } else {
                requestsCache.commitVote(requestId, outcome)
                requestsHandlers?.onVote(outcome)
                callback?.invoke(null, outcome)
            }
        }
    }

    /** Toggle follow. Removes both manual and auto-source rows on `false`. */
    fun followRequest(
        requestId: String,
        follow: Boolean,
        callback: ((Throwable?, studio.ritmus.feedback.api.RequestFollow?) -> Unit)? = null,
    ) {
        val rollback = requestsCache.applyOptimisticFollow(requestId, follow)
        val api = requestsApi() ?: return callback?.invoke(IllegalStateException("not init"), null) ?: Unit
        val identity = identityRef.get()?.load() ?: return callback?.invoke(IllegalStateException("identity"), null) ?: Unit
        scope.launch {
            val outcome = api.follow(requestId, identity.anonymousId, identity.externalId, follow)
            if (outcome == null) {
                rollback()
                callback?.invoke(IllegalStateException("follow failed"), null)
            } else {
                requestsCache.commitFollow(requestId, outcome)
                requestsHandlers?.onFollow(outcome)
                callback?.invoke(null, outcome)
            }
        }
    }

    /** Fetch comments for a request. */
    fun getComments(
        requestId: String,
        callback: (Throwable?, List<studio.ritmus.feedback.internal.requests.RequestComment>?) -> Unit,
    ) {
        val api = requestsApi() ?: return callback(IllegalStateException("not init"), null)
        val identity = identityRef.get()?.load() ?: return callback(IllegalStateException("identity"), null)
        scope.launch {
            val items = api.comments(requestId, identity.anonymousId, identity.externalId)
            if (items == null) callback(IllegalStateException("getComments failed"), null)
            else callback(null, items)
        }
    }

    /** Post a comment on a request. */
    fun postComment(
        requestId: String,
        body: String,
        callback: (Throwable?, studio.ritmus.feedback.internal.requests.RequestComment?) -> Unit,
    ) {
        val api = requestsApi() ?: return callback(IllegalStateException("not init"), null)
        val identity = identityRef.get()?.load() ?: return callback(IllegalStateException("identity"), null)
        scope.launch {
            val c = api.postComment(requestId, identity.anonymousId, identity.externalId, body)
            if (c == null) callback(IllegalStateException("postComment failed"), null)
            else callback(null, c)
        }
    }

    /** Register host-app callbacks for the request lifecycle. */
    fun setRequestsHandlers(handlers: studio.ritmus.feedback.api.RequestsHandlers) {
        requestsHandlers = handlers
    }
}
