package studio.usergist.feedback

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import studio.usergist.feedback.api.Consent
import studio.usergist.feedback.api.Environment
import studio.usergist.feedback.api.InAppCtaAction
import studio.usergist.feedback.api.InAppCtaClick
import studio.usergist.feedback.api.InAppDismissReason
import studio.usergist.feedback.api.InAppHandlers
import studio.usergist.feedback.api.PromptResponseInfo
import studio.usergist.feedback.api.PromptTheme
import studio.usergist.feedback.internal.Config
import studio.usergist.feedback.internal.ConsentStore
import studio.usergist.feedback.internal.EventQueue
import studio.usergist.feedback.internal.IdentityStore
import studio.usergist.feedback.internal.LocalInstructionDedupe
import studio.usergist.feedback.internal.MutationKind
import studio.usergist.feedback.internal.MutationPurpose
import studio.usergist.feedback.internal.MutationQueue
import studio.usergist.feedback.internal.SecureStore
import studio.usergist.feedback.internal.UserGistLogger
import studio.usergist.feedback.internal.Storage
import studio.usergist.feedback.internal.lifecycle.AppLifecycleObserver
import studio.usergist.feedback.internal.model.ArmedTrigger
import studio.usergist.feedback.internal.model.ClientPrompt
import studio.usergist.feedback.internal.model.FrequencyCaps
import studio.usergist.feedback.internal.model.IngestBatch
import studio.usergist.feedback.internal.model.IngestEvent
import studio.usergist.feedback.internal.model.EventPurpose
import studio.usergist.feedback.internal.model.toWire
import studio.usergist.feedback.internal.transport.ApiClient
import studio.usergist.feedback.internal.transport.Endpoints
import studio.usergist.feedback.internal.surveys.SdkSurveyAnswer
import studio.usergist.feedback.internal.surveys.SdkSurveyAttemptRequest
import studio.usergist.feedback.internal.surveys.SdkSurveyAttemptSession
import studio.usergist.feedback.internal.surveys.SdkSurveyCampaignWithFlow
import studio.usergist.feedback.internal.surveys.SdkSurveyCompleteBody
import studio.usergist.feedback.internal.surveys.SdkSurveyProgress
import studio.usergist.feedback.internal.surveys.SurveyHost
import studio.usergist.feedback.internal.surveys.SurveyPresentation
import studio.usergist.feedback.internal.surveys.SurveyStore
import studio.usergist.feedback.internal.triggers.CampaignRulesCache
import studio.usergist.feedback.internal.triggers.FrequencyCapStore
import studio.usergist.feedback.internal.triggers.RulesCache
import studio.usergist.feedback.internal.triggers.SegmentEvaluator
import studio.usergist.feedback.internal.triggers.TriggerMatcher
import studio.usergist.feedback.internal.triggers.UserState
import studio.usergist.feedback.internal.ui.CampaignPresentationEligibility
import studio.usergist.feedback.internal.ui.SdkModalCoordinator
import studio.usergist.feedback.internal.ui.PromptPresenter
import studio.usergist.feedback.internal.ui.InAppPresenter
import studio.usergist.feedback.internal.ui.ThemeResolver
import studio.usergist.feedback.internal.util.AnyMap
import studio.usergist.feedback.internal.util.ContextBuilder
import studio.usergist.feedback.internal.util.DateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/**
 * The SDK entry point. Matches the public API defined in DEV_PRD §6.
 *
 * Thread-safety: every public method is safe to call from any thread.
 * All heavy work is dispatched to an internal IO coroutine scope so
 * callers never block the UI thread. Cold-start cost is dominated by
 * filesystem reads on the IO dispatcher, not the synchronous part of
 * [initialize].
 */
object UserGist {

    data class SdkDiagnostic(
        val code: String = "sdk_error",
        val message: String,
        val occurredAt: String = DateTime.nowIso(),
    )

    // ---------------- Callbacks ----------------

    /** Invoked on the main thread when a prompt is shown. */
    @Volatile
    var onPromptShown: ((String) -> Unit)? = null
        set(value) {
            field = value
        }

    /** Invoked on the main thread when a response (including dismissal) is produced. */
    @Volatile
    var onResponse: ((PromptResponseInfo) -> Unit)? = null
        set(value) {
            field = value
        }

    /** Stable, workspace-scoped 21-character URL-safe anonymous identifier. */
    val anonymousId: String
        get() = identityRef.get()?.load()?.anonymousId ?: ""

    /** Stable identified-user ID accepted by the server, or null while anonymous. */
    val externalId: String?
        get() = identityRef.get()?.load()?.externalId

    // ---------------- Internal state ----------------

    private val initialized: AtomicBoolean = AtomicBoolean(false)
    private val configRef: AtomicReference<Config?> = AtomicReference(null)
    private val appContextRef: AtomicReference<Context?> = AtomicReference(null)
    private val identityRef: AtomicReference<IdentityStore?> = AtomicReference(null)
    private val consentRef: AtomicReference<ConsentStore?> = AtomicReference(null)
    private val queueRef: AtomicReference<EventQueue?> = AtomicReference(null)
    private val rulesRef: AtomicReference<RulesCache?> = AtomicReference(null)
    private val campaignRulesRef: AtomicReference<CampaignRulesCache?> = AtomicReference(null)
    private val capsRef: AtomicReference<FrequencyCapStore?> = AtomicReference(null)
    private val matcherRef: AtomicReference<TriggerMatcher?> = AtomicReference(null)
    private val apiRef: AtomicReference<ApiClient?> = AtomicReference(null)
    private val presenterRef: AtomicReference<PromptPresenter?> = AtomicReference(null)
    private val inAppPresenterRef: AtomicReference<InAppPresenter?> = AtomicReference(null)
    private val themeOverridesRef: AtomicReference<PromptTheme?> = AtomicReference(null)
    private val lifecycleRef: AtomicReference<AppLifecycleObserver?> = AtomicReference(null)
    private val storageRef: AtomicReference<Storage?> = AtomicReference(null)
    private val secureRef: AtomicReference<SecureStore?> = AtomicReference(null)
    private val subjectTokenRef: AtomicReference<String?> = AtomicReference(null)
    private val mutationRef: AtomicReference<MutationQueue?> = AtomicReference(null)
    private val surveyStoreRef: AtomicReference<SurveyStore?> = AtomicReference(null)
    private val localInstructionDedupeRef: AtomicReference<LocalInstructionDedupe?> =
        AtomicReference(null)
    private val subjectSessionMutex = Mutex()
    private val instructionMutex = Mutex()
    private val mutationMutex = Mutex()
    private val surveyCooldownByCampaign = HashMap<String, Long>()
    private val pendingPromptCapIds = HashSet<String>()
    private val pendingSurveyCapIds = HashSet<String>()
    private val appOpenLock = Any()
    private var appOpenPending = false
    private val resetGeneration = AtomicLong(0)
    private val resetInProgress = AtomicBoolean(false)

    @Volatile
    private var inAppHandlersRef: InAppHandlers = InAppHandlers()

    private val scopeExceptionHandler = CoroutineExceptionHandler { _, error ->
        if (error !is kotlinx.coroutines.CancellationException) {
            UserGistLogger.e("Unhandled UserGist coroutine failure", error)
        }
    }
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + scopeExceptionHandler)

    @Volatile
    private var flushJob: Job? = null

    @Volatile
    private var syncJob: Job? = null

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
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
        presentationPaused: Boolean = false,
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
                presentationPaused = presentationPaused,
            )
        }.onFailure { UserGistLogger.e("UserGist.initialize failed", it) }
    }

    /**
     * Sets or updates the logged-in user. [properties] are merged into
     * the user's property snapshot (server-side).
     */
    @JvmStatic
    @JvmOverloads
    fun identify(
        userId: String,
        subjectToken: String,
        properties: Map<String, Any?>? = null,
    ) {
        if (!initialized.get()) return
        if (userId.isBlank()) {
            UserGistLogger.w("UserGist.identify: userId is blank")
            return
        }
        if (!subjectToken.startsWith("st_")) {
            UserGistLogger.w("UserGist.identify requires a server-minted subject token")
            return
        }
        val props = if (consentRef.get()?.get()?.analytics == true) {
            AnyMap.toJsonObject(properties)
        } else {
            null
        }
        val identity = identityRef.get() ?: return
        val mutations = mutationRef.get() ?: return
        scope.launch {
            val payload = SdkIdentifyMutation(
                subjectToken = subjectToken,
                anonymousId = identity.load().anonymousId,
                externalId = userId,
                properties = props,
            )
            val id = mutations.enqueue(
                kind = MutationKind.IDENTIFY,
                purpose = MutationPurpose.ESSENTIAL,
                payload = json.encodeToJsonElement(
                    SdkIdentifyMutation.serializer(),
                    payload,
                ).jsonObject,
                dedupeKey = "identify:$userId",
            )
            flushMutations()
            if (mutations.has(id)) {
                UserGistLogger.w("UserGist.identify stored locally and pending retry")
            }
        }
    }

    /**
     * Records an event. Safe to call before consent — the event is
     * queued locally and transmitted once consent is granted.
     */
    @JvmStatic
    @JvmOverloads
    fun track(eventName: String, properties: Map<String, Any?>? = null) {
        trackWithPurpose(eventName, properties, EventPurpose.ANALYTICS)
    }

    private fun trackWithPurpose(
        eventName: String,
        properties: Map<String, Any?>?,
        purpose: EventPurpose,
    ) {
        if (!initialized.get()) return
        if (eventName.isBlank()) {
            UserGistLogger.w("UserGist.track: eventName is blank")
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
            purpose = purpose,
        )
        scope.launch {
            try {
                val queue = queueRef.get() ?: return@launch
                queue.enqueue(event)
                UserGistLogger.d("UserGist.track enqueued '$eventName' (queue=${queue.size()})")
                updateLocalUserStateForEvent(event)
                maybeFireTriggerFor(event)
                maybeFireCampaignsFor(event)
                if (shouldFlushNow()) flushAsync()
            } catch (e: Throwable) {
                UserGistLogger.w("UserGist.track dispatch failed", e)
            }
        }
    }

    /** Pause campaign UI without stopping analytics or dismissing an active surface. */
    @JvmStatic fun pausePresentation() { SdkModalCoordinator.setPaused(true) }

    /** Call after startup navigation and the loaded screen are ready. */
    @JvmStatic fun resumePresentation() { SdkModalCoordinator.setPaused(false) }

    /**
     * Updates consent. Transport remains blocked until at least one purpose is
     * granted. Newly granted analytics or feedback consent flushes eligible
     * events immediately.
     */
    @JvmStatic
    fun setConsent(purposes: Consent) {
        if (!initialized.get()) return
        try {
            val store = consentRef.get() ?: return
            val previous = store.get()
            val next = store.set(purposes)
            CampaignPresentationEligibility.updateConsent(next)
            if (purposes.analytics == false) {
                queueRef.get()?.removePurpose(EventPurpose.ANALYTICS)
            }
            if (purposes.feedback == false) {
                queueRef.get()?.removePurpose(EventPurpose.FEEDBACK)
                mutationRef.get()?.removePurpose(MutationPurpose.FEEDBACK)
                synchronized(pendingPromptCapIds) { pendingPromptCapIds.clear() }
            }
            if (purposes.survey == false) {
                mutationRef.get()?.removePurpose(MutationPurpose.SURVEY)
                synchronized(pendingSurveyCapIds) { pendingSurveyCapIds.clear() }
            }
            UserGistLogger.d("UserGist.setConsent: $next")
            enqueueConsentServer(next)
            if ((previous.analytics != true && next.analytics == true) ||
                (previous.feedback != true && next.feedback == true)
            ) {
                flushAsync()
            }
            if ((previous.feedback != true && next.feedback == true) ||
                (previous.survey != true && next.survey == true)
            ) {
                scope.launch {
                    refreshArmedTriggers()
                    emitPendingAppOpenIfReady()
                }
            }
        } catch (error: Throwable) {
            UserGistLogger.w("UserGist.setConsent failed", error)
        }
    }

    /** Revokes the subject session and clears all user-scoped local state. */
    @JvmStatic
    fun reset() {
        if (!initialized.get()) return
        if (!resetInProgress.compareAndSet(false, true)) return
        resetGeneration.incrementAndGet()
        CampaignPresentationEligibility.invalidateIdentity()
        apiRef.get()?.cancelAll()
        scope.launch {
            try {
                subjectSessionMutex.withLock {
                    mutationMutex.withLock {
                        if (subjectTokenRef.get() != null) {
                            apiRef.get()?.postJson(
                                path = Endpoints.SESSION_REVOKE,
                                body = EmptyPayload(),
                                serializer = EmptyPayload.serializer(),
                            )
                        }
                        identityRef.get()?.reset()
                        queueRef.get()?.clear()
                        mutationRef.get()?.clear()
                        capsRef.get()?.clear()
                        rulesRef.get()?.clear()
                        campaignRulesRef.get()?.clear()
                        surveyStoreRef.get()?.clear()
                        presenterRef.get()?.clear()
                        inAppPresenterRef.get()?.clear()
                        SurveyHost.clearWaiting()
                        consentRef.get()?.clear()
                        storageRef.get()?.let { it.delete(it.instructionStateFile) }
                        localInstructionDedupeRef.get()?.clear()
                        storageRef.get()?.let { it.delete(it.userStateFile) }
                        secureRef.get()?.delete(SecureStore.Key.SUBJECT_TOKEN)
                        subjectTokenRef.set(null)
                        studio.usergist.feedback.push.Push.reset()
                        synchronized(surveyCooldownByCampaign) {
                            surveyCooldownByCampaign.clear()
                        }
                        synchronized(pendingPromptCapIds) { pendingPromptCapIds.clear() }
                        synchronized(pendingSurveyCapIds) { pendingSurveyCapIds.clear() }
                        synchronized(userStateLock) { eventHistory.clear() }
                        synchronized(appOpenLock) { appOpenPending = false }
                        apiRef.get()?.setSubjectToken(null)
                    }
                }
                ensureSubjectSession(allowDuringReset = true)
                UserGistLogger.d("UserGist.reset complete")
            } catch (e: Throwable) {
                UserGistLogger.w("UserGist.reset failed", e)
            } finally {
                resetInProgress.set(false)
            }
        }
    }

    /** Applies host-app theme overrides used when rendering prompts. */
    @JvmStatic
    fun setThemeOverrides(theme: PromptTheme) {
        themeOverridesRef.set(theme)
        presenterRef.get()?.setThemeOverrides(theme)
        inAppPresenterRef.get()?.setThemeOverrides(theme)
    }

    /** Replaces the lifecycle callbacks for SDK-rendered in-app messages. */
    @JvmStatic
    fun setInAppHandlers(handlers: InAppHandlers) {
        inAppHandlersRef = handlers
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
        UserGistLogger.setDebug(enabled)
    }

    /** Receives bounded, non-throwing SDK diagnostics in production. */
    @JvmStatic
    fun setDiagnosticHandler(handler: ((SdkDiagnostic) -> Unit)?) {
        UserGistLogger.setDiagnosticHandler { message ->
            handler?.invoke(SdkDiagnostic(message = message))
        }
    }

    // ---------------- Surveys ----------------

    @JvmField
    @Volatile
    var surveyHandlers: studio.usergist.feedback.api.SurveyHandlers =
        studio.usergist.feedback.api.SurveyHandlers()

    /** Replaces the lifecycle callbacks for SDK-rendered surveys. */
    @JvmStatic
    fun setSurveyHandlers(handlers: studio.usergist.feedback.api.SurveyHandlers) {
        surveyHandlers = handlers
    }

    /** Fetches surveys currently open to this user. */
    fun getAvailableSurveys(
        callback: (List<studio.usergist.feedback.api.SurveySummary>) -> Unit,
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
        val api = apiRef.get() ?: run { callback(emptyList()); return }
        val identity = identityRef.get()?.load() ?: run { callback(emptyList()); return }
        scope.launch {
            val result = api.getJson(
                path = Endpoints.AVAILABLE_SURVEYS,
                query = mapOf(
                    "anonymousId" to identity.anonymousId,
                    "externalId" to identity.externalId,
                ),
                deserializer = AvailableSurveysEnvelope.serializer(),
            )
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                callback(result?.surveys?.map { it.toPublic() } ?: emptyList())
            }
        }
    }

    /** Fetches, resumes or creates, and presents the native survey flow. */
    fun openSurvey(surveyId: String, language: String? = null) {
        openSurveyInternal(surveyId, language, "on_demand")
    }

    /**
     * Handles a UserGist survey share link. Returns true when the URL is a
     * recognized UserGist survey link.
     */
    fun handleSurveyDeepLink(uri: android.net.Uri): Boolean {
        if (!initialized.get()) return false
        val pathToken = uri.pathSegments.takeIf { it.size >= 2 && it[0] == "s" }?.get(1)
        val queryToken = uri.getQueryParameter("survey")
        val token = pathToken ?: queryToken ?: return false
        if (token.isEmpty()) return false
        if (consentRef.get()?.get()?.allowsSurvey != true) return false
        val api = apiRef.get() ?: return false
        val identity = identityRef.get()?.load() ?: return false
        scope.launch {
            val result = api.postJsonWithResponse(
                path = Endpoints.RESOLVE_SURVEY_LINK,
                body = ResolveSurveyLinkPayload(
                    token = token,
                    anonymousId = identity.anonymousId,
                    externalId = identity.externalId,
                ),
                serializer = ResolveSurveyLinkPayload.serializer(),
                deserializer = ResolveSurveyLinkResponse.serializer(),
            )
            val surveyId = result?.surveyId ?: return@launch
            if (result.consentRequired) return@launch
            openSurveyInternal(surveyId, null, "link")
        }
        return true
    }

    private fun openSurveyInternal(
        surveyId: String,
        language: String?,
        source: String,
    ) {
        if (!initialized.get() || surveyId.isBlank()) return
        if (consentRef.get()?.get()?.allowsSurvey != true) return
        val api = apiRef.get() ?: return
        val identity = identityRef.get()?.load() ?: return
        val context = appContextRef.get() ?: return
        val isValid = CampaignPresentationEligibility.validator(CampaignPresentationEligibility.Purpose.SURVEY)
        scope.launch {
            try {
                ensureSubjectSession()
                val survey = campaignRulesRef.get()?.survey(surveyId)?.survey
                    ?: api.getJson(
                        path = Endpoints.survey(surveyId),
                        query = mapOf(
                            "anonymousId" to identity.anonymousId,
                            "externalId" to identity.externalId,
                            "language" to language,
                        ),
                        deserializer = SdkSurveyCampaignWithFlow.serializer(),
                    )
                if (survey == null) {
                    UserGistLogger.w("Unable to fetch survey $surveyId")
                    releasePendingSurvey(surveyId)
                    return@launch
                }
                val serverAttempt = api.postJsonWithResponse(
                    path = Endpoints.surveyAttempts(surveyId),
                    body = SdkSurveyAttemptRequest(
                        anonymousId = identity.anonymousId,
                        externalId = identity.externalId,
                        source = source,
                        language = language,
                        sdkVersion = BuildConfig.SDK_VERSION,
                        appVersion = appVersion(context),
                    ),
                    serializer = SdkSurveyAttemptRequest.serializer(),
                    deserializer = SdkSurveyAttemptSession.serializer(),
                ) ?: run {
                    UserGistLogger.w("Unable to create or resume survey attempt for $surveyId")
                    releasePendingSurvey(surveyId)
                    return@launch
                }
                val attempt = surveyStoreRef.get()?.merge(surveyId, serverAttempt)
                    ?: serverAttempt
                surveyStoreRef.get()?.upsert(surveyId, attempt)
                val presentation = surveyPresentation(survey, attempt)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    val activity = lifecycleRef.get()?.topActivity()
                    if (activity == null || !SurveyHost.present(activity, presentation, isValid)) {
                        UserGistLogger.w("Unable to present survey $surveyId: no resumed activity")
                        releasePendingSurvey(surveyId)
                    }
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                releasePendingSurvey(surveyId)
                UserGistLogger.w("openSurvey $surveyId failed", error)
            }
        }
    }

    private fun surveyPresentation(
        survey: SdkSurveyCampaignWithFlow,
        attempt: SdkSurveyAttemptSession,
    ): SurveyPresentation = SurveyPresentation(
        survey = survey,
        attempt = attempt,
        theme = ThemeResolver.merge(survey.theme, themeOverridesRef.get()),
        onShown = {
            scope.launch { recordSurveyShown(survey.id) }
            surveyHandlers.onShow?.invoke(survey.id)
        },
        onProgress = { attemptId, currentQuestionId, snapshot ->
            scope.launch {
                surveyStoreRef.get()?.update(attemptId, currentQuestionId, snapshot)
                apiRef.get()?.patchJsonWithResponse(
                    path = Endpoints.surveyProgress(attemptId),
                    body = SdkSurveyProgress(currentQuestionId, snapshot),
                    serializer = SdkSurveyProgress.serializer(),
                    deserializer = SdkOkResponse.serializer(),
                )
            }
        },
        onComplete = { attemptId, answers, callback ->
            val deliveryGeneration = resetGeneration.get()
            if (resetInProgress.get()) {
                scope.launch(Dispatchers.Main) { callback(false) }
            } else scope.launch {
                val mutations = mutationRef.get()
                if (mutations == null) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) { callback(false) }
                    return@launch
                }
                val body = SdkSurveyCompleteBody(
                    finalAnswers = answers.map { (questionId, value) ->
                        SdkSurveyAnswer(questionId, value)
                    },
                )
                val mutationId = mutations.enqueue(
                    kind = MutationKind.SURVEY_COMPLETE,
                    purpose = MutationPurpose.SURVEY,
                    payload = json.encodeToJsonElement(
                        SdkSurveyMutation.serializer(),
                        SdkSurveyMutation(
                            attemptId = attemptId,
                            body = json.encodeToJsonElement(
                                SdkSurveyCompleteBody.serializer(),
                                body,
                            ).jsonObject,
                        ),
                    ).jsonObject,
                    dedupeKey = "survey-complete:$attemptId",
                )
                val result = flushMutations()
                // Durable acceptance is the user-facing completion boundary.
                // A transient transport failure leaves the mutation encrypted
                // on disk for lifecycle retry and must not trap the survey UI.
                val accepted = mutationId !in result.permanentlyRejectedIds &&
                    !resetInProgress.get() &&
                    resetGeneration.get() == deliveryGeneration
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (accepted) {
                        surveyStoreRef.get()?.removeAttempt(attemptId)
                        surveyHandlers.onComplete?.invoke(survey.id, attemptId)
                    }
                    callback(accepted)
                }
            }
        },
        onAbandon = { attemptId, callback ->
            scope.launch {
                val persisted = runCatching {
                    mutationRef.get()?.enqueue(
                        kind = MutationKind.SURVEY_ABANDON,
                        purpose = MutationPurpose.SURVEY,
                        payload = json.encodeToJsonElement(
                            SdkSurveyMutation.serializer(),
                            SdkSurveyMutation(attemptId = attemptId),
                        ).jsonObject,
                        dedupeKey = "survey-abandon:$attemptId",
                    )
                }.getOrNull() != null
                if (persisted) {
                    surveyStoreRef.get()?.removeAttempt(attemptId)
                    flushMutations()
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (persisted) {
                        surveyHandlers.onAbandon?.invoke(survey.id, attemptId)
                    }
                    callback(persisted)
                }
            }
        },
    )

    private fun appVersion(context: Context): String? = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Throwable) {
        null
    }

    // ---------------- Push ----------------

    /** Internal: register an FCM token with the control plane. */
    @JvmStatic
    internal fun registerPushToken(token: String) {
        if (!initialized.get() || token.isBlank()) return
        scope.launch {
            try {
                // A fresh install can receive its first Firebase token while
                // the anonymous subject session is still being established.
                // Session recovery may rotate the installation identity, so
                // resolve the identity only after that work has completed.
                ensureSubjectSession()
                val api = apiRef.get() ?: return@launch
                val consent = consentRef.get()?.get() ?: return@launch
                if (!consent.allowsPush) {
                    UserGistLogger.d("push register skipped: consent not granted")
                    return@launch
                }
                val identity = identityRef.get()?.load() ?: return@launch
                val context = appContextRef.get()
                val appVersion = try {
                    context?.packageManager?.getPackageInfo(context.packageName, 0)?.versionName
                } catch (_: Throwable) {
                    null
                }
                val payload = SdkRegisterTokenPayload(
                    anonymousId = identity.anonymousId,
                    externalId = identity.externalId,
                    token = token,
                    platform = "android",
                    environment = if (configRef.get()?.environment == Environment.PRODUCTION) {
                        "production"
                    } else {
                        "sandbox"
                    },
                    language = java.util.Locale.getDefault().toLanguageTag(),
                    timezone = java.util.TimeZone.getDefault().id,
                    appVersion = appVersion,
                    sdkVersion = Config.SDK_VERSION,
                    optIn = true,
                )
                val registered = api.postJson(
                    path = Endpoints.PUSH_REGISTER_TOKEN,
                    body = payload,
                    serializer = SdkRegisterTokenPayload.serializer(),
                )
                if (registered) {
                    UserGistLogger.d("UserGist.registerPushToken complete")
                } else {
                    UserGistLogger.w("UserGist.registerPushToken did not reach the control plane")
                }
            } catch (e: Throwable) {
                UserGistLogger.w("UserGist.registerPushToken failed", e)
            }
        }
    }

    /** Internal: invalidate an FCM token when the host disables push. */
    @JvmStatic
    internal fun invalidatePushToken(token: String) {
        if (!initialized.get() || token.isBlank()) return
        val api = apiRef.get() ?: return
        val identity = identityRef.get()?.load() ?: return
        scope.launch {
            api.postJson(
                path = Endpoints.PUSH_INVALIDATE_TOKEN,
                body = SdkInvalidateTokenPayload(identity.anonymousId, token),
                serializer = SdkInvalidateTokenPayload.serializer(),
            )
        }
    }

    /** Internal: fetch the remote channel registry. */
    @JvmStatic
    internal fun fetchPushChannels(
        callback: (List<studio.usergist.feedback.push.UserGistPushChannel>) -> Unit,
    ) {
        if (!initialized.get()) {
            callback(emptyList())
            return
        }
        val api = apiRef.get() ?: run { callback(emptyList()); return }
        scope.launch {
            val result = api.getJson(
                path = Endpoints.PUSH_CHANNELS,
                query = emptyMap(),
                deserializer = PushChannelsEnvelope.serializer(),
            )
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                callback(result?.channels ?: emptyList())
            }
        }
    }

    /** Internal: update a user-owned channel subscription. */
    @JvmStatic
    internal fun setPushChannelSubscription(channelId: String, subscribed: Boolean) {
        if (!initialized.get() || channelId.isBlank()) return
        val api = apiRef.get() ?: return
        val identity = identityRef.get()?.load() ?: return
        scope.launch {
            api.postJson(
                path = Endpoints.PUSH_CHANNEL_SUBSCRIPTION,
                body = SdkChannelSubscriptionPayload(
                    anonymousId = identity.anonymousId,
                    channelId = channelId,
                    subscribed = subscribed,
                ),
                serializer = SdkChannelSubscriptionPayload.serializer(),
            )
        }
    }

    /** Internal: emit a $push_* analytics event carrying the payload envelope. */
    @JvmStatic
    internal fun trackPushEvent(
        eventName: String,
        msg: studio.usergist.feedback.push.UserGistPushMessage,
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
                UserGistLogger.w("UserGist.rebindPushToken failed", e)
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
                    occurredAt = DateTime.nowIso(),
                )
                api.postJson(
                    path = Endpoints.PUSH_APP_OPEN,
                    body = payload,
                    serializer = SdkAppOpenPayload.serializer(),
                )
            } catch (e: Throwable) {
                UserGistLogger.w("UserGist.reportPushAppOpen failed", e)
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
                    occurredAt = DateTime.nowIso(),
                    actionButton = actionButton,
                )
                api.postJson(
                    path = path,
                    body = payload,
                    serializer = SdkBeaconPayload.serializer(),
                )
            } catch (e: Throwable) {
                UserGistLogger.w("UserGist.pushBeacon $kind failed", e)
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
                    receivedAt = DateTime.nowIso(),
                )
                api.postJson(
                    path = Endpoints.PUSH_SILENT_ACK,
                    body = payload,
                    serializer = SdkSilentAckPayload.serializer(),
                )
            } catch (e: Throwable) {
                UserGistLogger.w("UserGist.ackSilentPush failed", e)
            }
        }
    }

    // ---------------- Internals ----------------

    @Synchronized
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
        presentationPaused: Boolean,
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
            UserGistLogger.setDebug(debug)
            configRef.set(config)
            return
        }
        if (initialized.get() && existing != null && existing.writeKey != writeKey) {
            UserGistLogger.w("UserGist.initialize called with a different writeKey; ignoring")
            return
        }

        SdkModalCoordinator.setPaused(presentationPaused)
        UserGistLogger.setDebug(debug)
        val storage = Storage(appCtx, writeKey)
        val secureStore = SecureStore.create(appCtx, writeKey)
        val identityStore = IdentityStore(storage, secureStore, json)
        val consentStore = ConsentStore(storage, secureStore, json)
        val eventQueue = EventQueue(storage, json, config.maxQueueSize)
        val mutationQueue = MutationQueue(storage, secureStore, json)
        val surveyStore = SurveyStore(storage, json)
        val rulesCache = RulesCache(storage, json)
        val campaignRules = CampaignRulesCache(storage, json)
        val capStore = FrequencyCapStore(storage, json)
        val matcher = TriggerMatcher(rulesCache, capStore)
        val apiClient = ApiClient(config.apiUrl, writeKey, json)

        configRef.set(config)
        appContextRef.set(appCtx)
        storageRef.set(storage)
        secureRef.set(secureStore)
        identityRef.set(identityStore)
        consentRef.set(consentStore)
        CampaignPresentationEligibility.updateConsent(consentStore.get())
        queueRef.set(eventQueue)
        mutationRef.set(mutationQueue)
        surveyStoreRef.set(surveyStore)
        localInstructionDedupeRef.set(LocalInstructionDedupe(storage, json))
        rulesRef.set(rulesCache)
        campaignRulesRef.set(campaignRules)
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
                onPromptShown = { promptId ->
                    scope.launch {
                        val reserved = synchronized(pendingPromptCapIds) {
                            pendingPromptCapIds.remove(promptId)
                        }
                        if (reserved) capsRef.get()?.recordShown(promptId)
                    }
                    trackWithPurpose(
                        "\$feedback_prompt_shown",
                        mapOf("promptId" to promptId),
                        EventPurpose.FEEDBACK,
                    )
                    this@UserGist.onPromptShown?.invoke(promptId)
                }
                onResponse = { info ->
                    submitPromptResponse(info)
                    this@UserGist.onResponse?.invoke(info)
                    inAppPresenterRef.get()?.retryPending()
                }
            }
            presenterRef.set(presenter)
            val inAppPresenter = InAppPresenter(lifecycle).apply {
                onShown = { messageId ->
                    trackWithPurpose(
                        "\$inapp_shown",
                        mapOf("message_id" to messageId),
                        EventPurpose.FEEDBACK,
                    )
                    inAppHandlersRef.onShow?.invoke(messageId)
                }
                onDismissed = { messageId, reason ->
                    trackWithPurpose(
                        if (reason == InAppDismissReason.AUTO) {
                            "\$inapp_auto_dismissed"
                        } else {
                            "\$inapp_dismissed"
                        },
                        mapOf("message_id" to messageId),
                        EventPurpose.FEEDBACK,
                    )
                    inAppHandlersRef.onDismiss?.invoke(messageId, reason)
                    presenterRef.get()?.retryPending()
                }
                onCta = { messageId, cta, index ->
                    trackWithPurpose(
                        "\$inapp_cta_clicked",
                        mapOf(
                            "message_id" to messageId,
                            "cta_index" to index,
                            "cta_action" to cta.action.name.lowercase(),
                            "cta_label" to cta.label,
                        ),
                        EventPurpose.FEEDBACK,
                    )
                    if (cta.action == InAppCtaAction.CUSTOM_EVENT && !cta.target.isNullOrBlank()) {
                        trackWithPurpose(
                            cta.target,
                            mapOf(
                                "message_id" to messageId,
                                "cta_index" to index,
                                "cta_label" to cta.label,
                            ),
                            EventPurpose.FEEDBACK,
                        )
                    }
                    val click = InAppCtaClick(
                        messageId = messageId,
                        action = cta.action,
                        target = cta.target,
                        label = cta.label,
                        index = index,
                        actionJson = cta.actionJson,
                    )
                    runCatching { inAppHandlersRef.onCtaClick?.invoke(click) }
                    if (cta.action == InAppCtaAction.JSON) {
                        cta.actionJson?.let {
                            runCatching { inAppHandlersRef.onJsonAction?.invoke(it, click) }
                        }
                    }
                    presenterRef.get()?.retryPending()
                }
            }
            inAppPresenterRef.set(inAppPresenter)
        } else {
            UserGistLogger.w("UserGist.initialize: context is not an Application; prompts will not render")
        }

        // Warm the stores + bootstrap rules cache off the hot path.
        scope.launch {
            try {
                identityStore.load()
                ensureSubjectSession()
                flushMutations()
                restoreLocalUserState(storage)
                rulesCache.bootstrapFromDisk()
                campaignRules.bootstrapFromDisk()
                pollInstructions()
                UserGistLogger.d("UserGist.initialize: background warmup complete")
            } catch (e: Throwable) {
                UserGistLogger.w("UserGist.initialize warmup failed", e)
            }
        }

        initialized.set(true)

        startFlushLoop()
        startTriggerSyncLoop()
    }

    /** Establishes the authenticated anonymous session exactly once. If a
     * persisted credential can no longer prove ownership of its anonymous
     * alias, rotate the installation identity before minting a new session. */
    private suspend fun ensureSubjectSession(allowDuringReset: Boolean = false) {
        val generation = resetGeneration.get()
        if (resetInProgress.get() && !allowDuringReset) {
            throw kotlinx.coroutines.CancellationException("UserGist reset in progress")
        }
        if (subjectTokenRef.get() != null) return
        subjectSessionMutex.withLock {
            ensureCurrentGeneration(generation, allowDuringReset)
            if (subjectTokenRef.get() != null) return
            val api = apiRef.get() ?: return
            val identity = identityRef.get() ?: return
            val secure = secureRef.get()
                ?: throw IllegalStateException("Secure persistence is unavailable")
            val persisted = secure.read(SecureStore.Key.SUBJECT_TOKEN)
            api.setSubjectToken(persisted)
            val firstAttempt = api.postJsonWithResponseDetailed(
                path = Endpoints.SESSION,
                body = SdkSessionRequest(identity.load().anonymousId),
                serializer = SdkSessionRequest.serializer(),
                deserializer = SdkSessionResponse.serializer(),
                requiresSubject = false,
                idempotent = false,
            )
            ensureCurrentGeneration(generation, allowDuringReset)
            var session = firstAttempt.value
            val mayRotate = firstAttempt.call.status in setOf(401, 403, 409)
            if (session == null && mayRotate) {
                if (persisted != null) secure.delete(SecureStore.Key.SUBJECT_TOKEN)
                api.setSubjectToken(null)
                val rotated = identity.reset()
                session = api.postJsonWithResponse(
                    path = Endpoints.SESSION,
                    body = SdkSessionRequest(rotated.anonymousId),
                    serializer = SdkSessionRequest.serializer(),
                    deserializer = SdkSessionResponse.serializer(),
                    requiresSubject = false,
                    idempotent = false,
                )
                ensureCurrentGeneration(generation, allowDuringReset)
            }
            val token = session?.subjectToken
            if (token == null || !token.startsWith("st_")) {
                api.setSubjectToken(null)
                throw IllegalStateException("Unable to establish UserGist subject session")
            }
            if (!secure.write(SecureStore.Key.SUBJECT_TOKEN, token)) {
                api.setSubjectToken(null)
                throw IllegalStateException("Unable to persist UserGist subject session")
            }
            ensureCurrentGeneration(generation, allowDuringReset)
            subjectTokenRef.set(token)
            api.setSubjectToken(token)
        }
    }

    private fun ensureCurrentGeneration(generation: Long, allowDuringReset: Boolean = false) {
        if ((resetInProgress.get() && !allowDuringReset) || resetGeneration.get() != generation) {
            throw kotlinx.coroutines.CancellationException("UserGist operation superseded by reset")
        }
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
                    UserGistLogger.w("UserGist flush loop error", e)
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
                requestAppOpen()
                pollInstructions()
            } catch (_: Throwable) { /* loop below handles recovery */ }
            while (isActive) {
                try {
                    delay(config.triggerSyncIntervalMs)
                    refreshArmedTriggers()
                    pollInstructions()
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    UserGistLogger.w("UserGist sync loop error", e)
                }
            }
        }
    }

    private fun onForeground() {
        UserGistLogger.d("UserGist: app entered foreground — refreshing armed triggers + flushing")
        flushAsync()
        scope.launch {
            refreshArmedTriggers()
            requestAppOpen()
        }
        scope.launch { pollInstructions() }
        presenterRef.get()?.retryPending()
        inAppPresenterRef.get()?.retryPending()
    }

    private fun onBackground() {
        UserGistLogger.d("UserGist: app entered background — flushing")
        flushAsync()
    }

    private fun requestAppOpen() {
        val shouldEmit = synchronized(appOpenLock) {
            if (consentRef.get()?.get()?.feedback == true) {
                appOpenPending = false
                true
            } else {
                appOpenPending = true
                false
            }
        }
        if (shouldEmit) {
            trackWithPurpose("\$app_open", null, EventPurpose.FEEDBACK)
        }
    }

    private fun emitPendingAppOpenIfReady() {
        val shouldEmit = synchronized(appOpenLock) {
            if (appOpenPending && consentRef.get()?.get()?.feedback == true) {
                appOpenPending = false
                true
            } else {
                false
            }
        }
        if (shouldEmit) {
            trackWithPurpose("\$app_open", null, EventPurpose.FEEDBACK)
        }
    }

    private fun flushAsync() {
        scope.launch { flushOnce() }
    }

    private suspend fun flushOnce() {
        ensureSubjectSession()
        flushMutations()
        val consent = consentRef.get()?.get() ?: return
        if (consent.analytics != true && consent.feedback != true) return
        val queue = queueRef.get() ?: return
        val api = apiRef.get() ?: return
        val config = configRef.get() ?: return
        val appCtx = appContextRef.get() ?: return
        val pending = queue.peek(Int.MAX_VALUE).toMutableList()
        if (pending.isEmpty()) return
        val acknowledgedIds = LinkedHashSet<String>()
        try {
            while (pending.isNotEmpty()) {
                val allowed = pending.filter { event ->
                    if (event.purpose == EventPurpose.ANALYTICS) {
                        consent.analytics == true
                    } else {
                        consent.feedback == true
                    }
                }
                val first = allowed.firstOrNull() ?: break
                val events = allowed.filter {
                    it.anonymousId == first.anonymousId && it.externalId == first.externalId
                }.take(config.flushBatchSize)
                if (events.isEmpty()) break
                val context = ContextBuilder.build(
                    context = appCtx,
                    anonymousId = first.anonymousId,
                    externalId = first.externalId,
                )
                val result = api.postJsonDetailed(
                    path = Endpoints.INGEST,
                    body = IngestBatch(events = events.map { it.toWire() }, context = context),
                    serializer = IngestBatch.serializer(),
                )
                if (result.success) {
                    val sentIds = events.mapTo(HashSet()) { it.eventId }
                    acknowledgedIds += sentIds
                    pending.removeAll { it.eventId in sentIds }
                    UserGistLogger.d("UserGist.flush sent ${events.size} events")
                } else {
                    val permanent = result.status != null &&
                        result.status in 400..499 && result.status != 429
                    if (permanent) {
                        if (events.size == 1) {
                            acknowledgedIds += first.eventId
                            pending.removeAll { it.eventId == first.eventId }
                            UserGistLogger.w(
                                "Quarantined permanently rejected event ${first.eventId}",
                            )
                            continue
                        }
                        val single = api.postJsonDetailed(
                            path = Endpoints.INGEST,
                            body = IngestBatch(events = listOf(first.toWire()), context = context),
                            serializer = IngestBatch.serializer(),
                        )
                        val singlePermanent = single.status != null &&
                            single.status in 400..499 && single.status != 429
                        if (single.success || singlePermanent) {
                            acknowledgedIds += first.eventId
                            pending.removeAll { it.eventId == first.eventId }
                            if (!single.success) {
                                UserGistLogger.w(
                                    "Quarantined permanently rejected event ${first.eventId}",
                                )
                            }
                            continue
                        }
                    }
                    UserGistLogger.d("UserGist.flush failed for ${events.size} events; will retry")
                    break
                }
            }
        } finally {
            queue.remove(acknowledgedIds)
        }
        pollInstructions()
    }

    private suspend fun pollInstructions() = instructionMutex.withLock {
        try {
            ensureSubjectSession()
            val storage = storageRef.get() ?: return@withLock
            val api = apiRef.get() ?: return@withLock
            val stored = storage.readText(storage.instructionStateFile)?.let { text ->
                runCatching { json.decodeFromString<InstructionState>(text) }.getOrNull()
            } ?: InstructionState()
            val seen = LinkedHashSet(stored.seen)
            val result = api.getJson(
                path = Endpoints.INSTRUCTIONS,
                query = mapOf("after" to stored.cursor.toString(), "limit" to "100",
                    "protocolVersion" to "2", "platform" to "android", "anonymousId" to anonymousId,
                    "sdkVersion" to BuildConfig.SDK_VERSION),
                deserializer = InstructionEnvelope.serializer(),
            ) ?: return@withLock
            if (result.instructions.isEmpty()) return@withLock
            val handled = mutableListOf<Long>()
            for (instruction in result.instructions) {
                if (instruction.id <= 0) continue
                handled += instruction.id
                if (seen.contains(instruction.id)) continue
                dispatchInstruction(instruction)
                seen += instruction.id
                while (seen.size > 200) seen.remove(seen.first())
                val persisted = storage.writeText(
                    storage.instructionStateFile,
                    json.encodeToString(
                        InstructionState.serializer(),
                        InstructionState(stored.cursor, seen.toList()),
                    ),
                )
                if (!persisted) error("Failed to persist instruction dedupe state")
            }
            if (handled.isEmpty()) return@withLock
            val cursor = maxOf(stored.cursor, handled.max())
            val persisted = storage.writeText(
                storage.instructionStateFile,
                json.encodeToString(
                    InstructionState.serializer(),
                    InstructionState(cursor, seen.toList()),
                ),
            )
            if (!persisted) error("Failed to persist instruction cursor")
            api.postJson(
                path = Endpoints.INSTRUCTIONS_ACK,
                body = InstructionAck(handled),
                serializer = InstructionAck.serializer(),
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            UserGistLogger.w("UserGist instruction poll failed", e)
        }
    }

    private fun dispatchInstruction(instruction: SdkInstruction) {
        val payload = instruction.payload
        when (instruction.type) {
            "prompt.show" -> {
                if (consentRef.get()?.get()?.feedback != true) return
                val promptId = (payload["promptId"] as? JsonPrimitive)?.content ?: return
                val promptElement = payload["prompt"] ?: return
                val prompt = runCatching {
                    json.decodeFromJsonElement(ClientPrompt.serializer(), promptElement)
                }.getOrElse {
                    UserGistLogger.w("Invalid prompt.show instruction", it)
                    return
                }
                val triggerEventId = (payload["triggerEventId"] as? JsonPrimitive)?.content
                if (triggerEventId != null && consumeLocalInstruction(
                        "prompt.show",
                        promptId,
                        triggerEventId,
                    )
                ) return
                present(
                    ArmedTrigger(
                        promptId = promptId,
                        eventName = "server",
                        frequency = FrequencyCaps(),
                        prompt = prompt,
                    ),
                )
            }
            "survey.offer" -> {
                if (consentRef.get()?.get()?.allowsSurvey != true) return
                val surveyId = (payload["surveyId"] as? JsonPrimitive)?.content ?: return
                val triggerEventId = (payload["triggerEventId"] as? JsonPrimitive)?.content
                if (triggerEventId != null && consumeLocalInstruction(
                        "survey.offer",
                        surveyId,
                        triggerEventId,
                    )
                ) return
                val name = (payload["name"] as? JsonPrimitive)?.content ?: ""
                val source = (payload["source"] as? JsonPrimitive)?.content ?: "triggered"
                val summary = studio.usergist.feedback.api.SurveySummary(
                    id = surveyId,
                    name = name,
                    mode = "triggered",
                    source = source,
                )
                deliverSurveyInvite(summary, surveyId, source)
            }
            "inapp.show" -> {
                if (consentRef.get()?.get()?.feedback != true) return
                val messageElement = payload["message"] ?: return
                val message = runCatching {
                    json.decodeFromJsonElement(
                        studio.usergist.feedback.api.ArmedInAppMessage.serializer(),
                        messageElement,
                    )
                }.getOrElse {
                    UserGistLogger.w("Invalid inapp.show instruction", it)
                    return
                }
                if (message.messageId.isBlank() || message.title.isBlank()) return
                val triggerEventId = (payload["triggerEventId"] as? JsonPrimitive)?.content
                if (triggerEventId != null && consumeLocalInstruction(
                        "inapp.show",
                        message.messageId,
                        triggerEventId,
                    )
                ) return
                val presenter = inAppPresenterRef.get() ?: return
                val isValid = CampaignPresentationEligibility.validator(CampaignPresentationEligibility.Purpose.FEEDBACK)
                scope.launch {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        presenter.present(message, isValid)
                    }
                }
            }
            else -> if (instruction.type.startsWith("request.")) {
                studio.usergist.feedback.push.Push.emitSdkEvent(
                    "\$${instruction.type.replace('.', '_')}",
                    AnyMap.fromJsonObject(payload).orEmpty(),
                )
            }
        }
    }

    private suspend fun refreshArmedTriggers() {
        ensureSubjectSession()
        val consent = consentRef.get() ?: return
        val currentConsent = consent.get()
        if (!currentConsent.allowsTransport) {
            UserGistLogger.d("UserGist.refreshArmedTriggers skipped — no granted purpose")
            return
        }
        val rules = rulesRef.get() ?: return
        val api = apiRef.get() ?: return
        val identity = identityRef.get()?.load() ?: return
        if (currentConsent.feedback == true) {
            rules.refresh(api, identity.anonymousId, identity.externalId)
        }
        campaignRulesRef.get()?.refresh(
            apiClient = api,
            anonymousId = identity.anonymousId,
            externalId = identity.externalId,
            includeSurveys = currentConsent.survey == true,
            includeInApp = currentConsent.feedback == true,
        )
    }

    private fun maybeFireTriggerFor(event: IngestEvent) {
        val matcher = matcherRef.get() ?: return
        val consent = consentRef.get() ?: return
        if (consent.get().feedback != true) {
            UserGistLogger.d("UserGist: trigger eval skipped for '${event.name}' — consent")
            return
        }
        val user = buildUserState()
        val armed = matcher.match(event.name, user) ?: return
        val reserved = synchronized(pendingPromptCapIds) {
            pendingPromptCapIds.add(armed.promptId)
        }
        if (!reserved) return
        rememberLocalInstruction("prompt.show", armed.promptId, event.eventId)
        present(armed)
    }

    private fun maybeFireCampaignsFor(event: IngestEvent) {
        val rules = campaignRulesRef.get() ?: return
        val consent = consentRef.get()?.get() ?: return
        val user = buildUserState()

        if (consent.survey == true) {
            for (armed in rules.surveysFor(event.name)) {
                if (armed.clientSideEligible == false) continue
                if (!SegmentEvaluator.matches(armed.segmentRules, user)) continue
                val cap = FrequencyCaps(
                    perPromptDays = armed.frequencyCap.perCampaignDays,
                    perUserDays = armed.frequencyCap.perPillarDays,
                )
                val capKey = "survey:${armed.campaignId}"
                if (capsRef.get()?.allows(capKey, cap) == false) continue
                val cooldown = armed.cooldownSeconds ?: 0
                val now = System.currentTimeMillis()
                val cooldownBlocked = synchronized(surveyCooldownByCampaign) {
                    val last = surveyCooldownByCampaign[armed.campaignId]
                    last != null && cooldown > 0 && now - last < cooldown * 1_000
                }
                if (cooldownBlocked) continue
                val reserved = synchronized(pendingSurveyCapIds) {
                    pendingSurveyCapIds.add(armed.campaignId)
                }
                if (!reserved) continue

                rememberLocalInstruction("survey.offer", armed.campaignId, event.eventId)
                val summary = studio.usergist.feedback.api.SurveySummary(
                    id = armed.campaignId,
                    name = armed.survey.name,
                    mode = "triggered",
                    source = "triggered",
                )
                deliverSurveyInvite(summary, armed.campaignId, "triggered")
                break
            }
        }

        if (consent.feedback == true) {
            for (message in rules.inAppFor(event.name)) {
                if (message.clientSideEligible == false) continue
                rememberLocalInstruction("inapp.show", message.messageId, event.eventId)
                val presenter = inAppPresenterRef.get() ?: break
                val isValid = CampaignPresentationEligibility.validator(CampaignPresentationEligibility.Purpose.FEEDBACK)
                scope.launch {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        presenter.present(message, isValid)
                    }
                }
                break
            }
        }
    }

    private fun deliverSurveyInvite(
        summary: studio.usergist.feedback.api.SurveySummary,
        surveyId: String,
        source: String,
    ) {
        val handler = surveyHandlers.onInvite
        if (handler == null) {
            openSurveyInternal(surveyId, null, source)
            return
        }
        scope.launch(Dispatchers.Main) {
            handler(summary)
        }
    }

    private fun recordSurveyShown(surveyId: String) {
        val reserved = synchronized(pendingSurveyCapIds) {
            pendingSurveyCapIds.remove(surveyId)
        }
        if (!reserved) return
        capsRef.get()?.recordShown("survey:$surveyId")
        synchronized(surveyCooldownByCampaign) {
            surveyCooldownByCampaign[surveyId] = System.currentTimeMillis()
        }
    }

    private fun releasePendingSurvey(surveyId: String) {
        synchronized(pendingSurveyCapIds) { pendingSurveyCapIds.remove(surveyId) }
    }

    private fun localInstructionKey(type: String, refId: String, eventId: String): String =
        "$type:$refId:event:$eventId"

    private fun rememberLocalInstruction(type: String, refId: String, eventId: String) {
        localInstructionDedupeRef.get()?.remember(localInstructionKey(type, refId, eventId))
    }

    private fun consumeLocalInstruction(type: String, refId: String, eventId: String): Boolean =
        localInstructionDedupeRef.get()?.consume(localInstructionKey(type, refId, eventId)) == true

    private fun present(trigger: ArmedTrigger) {
        val presenter = presenterRef.get()
        if (presenter == null) {
            synchronized(pendingPromptCapIds) { pendingPromptCapIds.remove(trigger.promptId) }
            return
        }
        val isValid = CampaignPresentationEligibility.validator(CampaignPresentationEligibility.Purpose.FEEDBACK)
        scope.launch {
            try {
                val accepted = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    presenter.present(trigger.prompt, isValid)
                }
                if (!accepted) {
                    synchronized(pendingPromptCapIds) {
                        pendingPromptCapIds.remove(trigger.promptId)
                    }
                    UserGistLogger.w("Unable to queue prompt ${trigger.promptId}")
                }
            } catch (e: Throwable) {
                synchronized(pendingPromptCapIds) {
                    pendingPromptCapIds.remove(trigger.promptId)
                }
                UserGistLogger.w("UserGist.present failed", e)
            }
        }
    }

    // ---------------- Local user-state model ----------------

    private val userStateLock = Any()

    private var eventHistory: MutableMap<String, MutableList<Long>> = mutableMapOf()

    private fun updateLocalUserStateForEvent(event: IngestEvent) {
        synchronized(userStateLock) {
            val timestamp = DateTime.parseIso(event.timestamp) ?: System.currentTimeMillis()
            val history = eventHistory.getOrPut(event.name) { mutableListOf() }
            history += timestamp
            while (history.size > MAX_HISTORY_PER_EVENT) history.removeAt(0)
            persistLocalUserStateLocked()
        }
    }

    private fun restoreLocalUserState(storage: Storage) {
        val restored = storage.readText(storage.userStateFile)?.let { raw ->
            runCatching { json.decodeFromString<UserStateHistory>(raw) }.getOrNull()
        } ?: return
        synchronized(userStateLock) {
            eventHistory = restored.history.mapValues {
                it.value.takeLast(MAX_HISTORY_PER_EVENT).toMutableList()
            }.toMutableMap()
        }
    }

    private fun persistLocalUserStateLocked() {
        val storage = storageRef.get() ?: return
        val snapshot = UserStateHistory(
            history = eventHistory.mapValues { it.value.toList() },
        )
        runCatching { json.encodeToString(UserStateHistory.serializer(), snapshot) }
            .onSuccess { storage.writeText(storage.userStateFile, it) }
            .onFailure { UserGistLogger.w("User state persist failed", it) }
    }

    private fun buildUserState(): UserState {
        val identity = identityRef.get()?.load()
        val props = HashMap<String, Any?>()
        identity?.userProperties?.let { jo ->
            AnyMap.fromJsonObject(jo)?.let { props.putAll(it) }
        }
        identity?.externalId?.let { props["external_id"] = it }
        synchronized(userStateLock) {
            val now = System.currentTimeMillis()
            val counts = eventHistory.mapValues { (_, stamps) ->
                trackedWindowsDays.associateWith { days ->
                    val cutoff = now - days.toLong() * 86_400_000L
                    stamps.count { it >= cutoff }
                }
            }
            val last = eventHistory.mapNotNull { (name, stamps) ->
                stamps.lastOrNull()?.let { name to it }
            }.toMap()
            return UserState(
                properties = props,
                eventCounts = counts,
                lastEventAt = last,
            )
        }
    }

    private fun shouldFlushNow(): Boolean {
        val queue = queueRef.get() ?: return false
        val config = configRef.get() ?: return false
        return queue.size() >= config.flushBatchSize
    }

    private fun enqueueConsentServer(consent: Consent) {
        val api = apiRef.get() ?: return
        val identity = identityRef.get()?.load() ?: return
        scope.launch {
            try {
                ensureSubjectSession()
                val payload = SdkConsentPayload(
                    anonymousId = identity.anonymousId,
                    externalId = identity.externalId,
                    purposes = SdkConsentPayload.Purposes(
                        analytics = consent.analytics == true,
                        feedback = consent.feedback == true,
                        push = consent.push == true,
                        survey = consent.survey == true,
                    ),
                    version = consentRef.get()?.version() ?: 0,
                    effectiveAt = consentRef.get()?.updatedAt()
                        ?: DateTime.nowIso(),
                )
                api.postJson(
                    path = Endpoints.CONSENT,
                    body = payload,
                    serializer = SdkConsentPayload.serializer(),
                )
            } catch (e: Throwable) {
                UserGistLogger.w("UserGist.consent send failed", e)
            }
        }
    }

    private fun submitPromptResponse(info: PromptResponseInfo) {
        val identity = identityRef.get()?.load() ?: return
        val answers = info.answers.map { answer ->
            val value: JsonElement = when (val raw = answer.value) {
                is studio.usergist.feedback.api.PromptAnswerValue.Number -> JsonPrimitive(raw.value)
                is studio.usergist.feedback.api.PromptAnswerValue.Text -> JsonPrimitive(raw.value)
                is studio.usergist.feedback.api.PromptAnswerValue.Choices ->
                    JsonArray(raw.ids.map(::JsonPrimitive))
                studio.usergist.feedback.api.PromptAnswerValue.None -> JsonNull
            }
            SdkResponseAnswer(answer.questionId, value)
        }
        val payload = SdkResponsePayload(
            idempotencyKey = UUID.randomUUID().toString(),
            promptId = info.promptId,
            anonymousId = identity.anonymousId,
            externalId = identity.externalId,
            answers = answers,
            dismissed = info.dismissed,
            latencyMs = info.latencyMs.coerceAtMost(3_600_000).toInt(),
        )
        trackWithPurpose(
            "\$feedback_response",
            mapOf(
                "promptId" to info.promptId,
                "dismissed" to info.dismissed,
                "latencyMs" to info.latencyMs,
            ),
            EventPurpose.FEEDBACK,
        )
        scope.launch {
            val mutations = mutationRef.get() ?: return@launch
            mutations.enqueue(
                kind = MutationKind.FEEDBACK_RESPONSE,
                purpose = MutationPurpose.FEEDBACK,
                payload = json.encodeToJsonElement(
                    SdkResponsePayload.serializer(),
                    payload,
                ).jsonObject,
            )
            flushMutations()
        }
    }

    private data class MutationFlushResult(
        val permanentlyRejectedIds: Set<String> = emptySet(),
    )

    private suspend fun flushMutations(): MutationFlushResult = mutationMutex.withLock {
        if (resetInProgress.get()) return@withLock MutationFlushResult()
        val generation = resetGeneration.get()
        val rejected = LinkedHashSet<String>()
        val mutations = mutationRef.get() ?: return@withLock MutationFlushResult()
        val api = apiRef.get() ?: return@withLock MutationFlushResult()
        while (mutations.size() > 0) {
            if (resetInProgress.get() || resetGeneration.get() != generation) {
                return@withLock MutationFlushResult(rejected)
            }
            val mutation = mutations.peek() ?: break
            val consent = consentRef.get()?.get() ?: Consent()
            if (mutation.purpose == MutationPurpose.FEEDBACK && consent.feedback != true) break
            if (mutation.purpose == MutationPurpose.SURVEY && consent.survey != true) break
            val result = when (mutation.kind) {
                MutationKind.IDENTIFY -> {
                    val payload = runCatching {
                        json.decodeFromJsonElement(
                            SdkIdentifyMutation.serializer(),
                            mutation.payload,
                        )
                    }.getOrNull()
                    if (payload == null || !payload.subjectToken.startsWith("st_")) {
                        mutations.remove(mutation.id)
                        continue
                    }
                    val call = api.postJsonDetailed(
                        path = Endpoints.IDENTIFY,
                        body = SdkIdentifyPayload(
                            anonymousId = payload.anonymousId,
                            externalId = payload.externalId,
                            properties = payload.properties,
                        ),
                        serializer = SdkIdentifyPayload.serializer(),
                        subjectTokenOverride = payload.subjectToken,
                    )
                    if (resetInProgress.get() || resetGeneration.get() != generation) {
                        return@withLock MutationFlushResult(rejected)
                    }
                    if (call.success) {
                        val persisted = secureRef.get()?.write(
                            SecureStore.Key.SUBJECT_TOKEN,
                            payload.subjectToken,
                        ) == true
                        if (!persisted) {
                            break
                        }
                        subjectTokenRef.set(payload.subjectToken)
                        api.setSubjectToken(payload.subjectToken)
                        if (identityRef.get()?.load()?.externalId != payload.externalId) {
                            CampaignPresentationEligibility.invalidateIdentity()
                        }
                        identityRef.get()?.update { current ->
                            current.withExternalId(payload.externalId, payload.properties)
                        }
                        studio.usergist.feedback.push.Push.rebind(payload.externalId)
                        if (consent.analytics == true) {
                            trackWithPurpose(
                                "\$identify",
                                AnyMap.fromJsonObject(payload.properties ?: JsonObject(emptyMap())),
                                EventPurpose.ANALYTICS,
                            )
                        }
                    }
                    call
                }
                MutationKind.FEEDBACK_RESPONSE -> {
                    val payload = runCatching {
                        json.decodeFromJsonElement(
                            SdkResponsePayload.serializer(),
                            mutation.payload,
                        )
                    }.getOrNull()
                    if (payload == null) {
                        mutations.remove(mutation.id)
                        continue
                    }
                    api.postJsonDetailed(
                        path = Endpoints.RESPONSES,
                        body = payload,
                        serializer = SdkResponsePayload.serializer(),
                    )
                }
                MutationKind.SURVEY_COMPLETE, MutationKind.SURVEY_ABANDON -> {
                    val payload = runCatching {
                        json.decodeFromJsonElement(
                            SdkSurveyMutation.serializer(),
                            mutation.payload,
                        )
                    }.getOrNull()
                    if (payload == null) {
                        mutations.remove(mutation.id)
                        continue
                    }
                    val path = if (mutation.kind == MutationKind.SURVEY_COMPLETE) {
                        Endpoints.surveyComplete(payload.attemptId)
                    } else {
                        Endpoints.surveyAbandon(payload.attemptId)
                    }
                    api.postJsonDetailed(
                        path = path,
                        body = payload.body ?: JsonObject(emptyMap()),
                        serializer = JsonObject.serializer(),
                    )
                }
            }
            if (resetInProgress.get() || resetGeneration.get() != generation) {
                return@withLock MutationFlushResult(rejected)
            }
            if (result.success) {
                mutations.remove(mutation.id)
                continue
            }
            val permanent = result.status != null &&
                result.status in 400..499 && result.status != 429
            if (permanent) {
                mutations.remove(mutation.id)
                rejected += mutation.id
                UserGistLogger.w("Quarantined permanently rejected ${mutation.kind} mutation")
                continue
            }
            break
        }
        MutationFlushResult(rejected)
    }

    // ---------------- Wire payloads ----------------

    @kotlinx.serialization.Serializable
    internal data class SdkIdentifyPayload(
        val anonymousId: String,
        val externalId: String,
        val properties: JsonObject? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class SdkIdentifyMutation(
        val subjectToken: String,
        val anonymousId: String,
        val externalId: String,
        val properties: JsonObject? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class SdkSurveyMutation(
        val attemptId: String,
        val body: JsonObject? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class SdkConsentPayload(
        val anonymousId: String,
        val externalId: String? = null,
        val purposes: Purposes,
        val version: Int,
        val effectiveAt: String,
    ) {
        @kotlinx.serialization.Serializable
        internal data class Purposes(
            val analytics: Boolean,
            val feedback: Boolean,
            val push: Boolean,
            val survey: Boolean,
        )
    }

    @kotlinx.serialization.Serializable
    internal data class EmptyPayload(val unused: String? = null)

    @kotlinx.serialization.Serializable
    internal data class SdkOkResponse(val ok: Boolean = true)

    @kotlinx.serialization.Serializable
    internal data class SdkSessionRequest(val anonymousId: String)

    @kotlinx.serialization.Serializable
    internal data class SdkSessionResponse(
        val subjectToken: String,
        val subjectId: String,
        val expiresAt: String,
    )

    @kotlinx.serialization.Serializable
    internal data class SdkResponsePayload(
        val idempotencyKey: String,
        val promptId: String,
        val anonymousId: String,
        val externalId: String? = null,
        val answers: List<SdkResponseAnswer>,
        val dismissed: Boolean,
        val latencyMs: Int,
    )

    @kotlinx.serialization.Serializable
    internal data class SdkResponseAnswer(
        val questionId: String,
        val value: JsonElement,
    )

    @kotlinx.serialization.Serializable
    internal data class SdkInstruction(
        val id: Long,
        val type: String,
        val payload: JsonObject,
        val emittedAt: String? = null,
        val expiresAt: String? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class InstructionEnvelope(
        val instructions: List<SdkInstruction> = emptyList(),
    )

    @kotlinx.serialization.Serializable
    internal data class InstructionAck(val ids: List<Long>)

    @kotlinx.serialization.Serializable
    internal data class InstructionState(
        val cursor: Long = 0,
        val seen: List<Long> = emptyList(),
    )

    @kotlinx.serialization.Serializable
    internal data class AvailableSurveysEnvelope(
        val surveys: List<SdkSurveySummary> = emptyList(),
    )

    @kotlinx.serialization.Serializable
    internal data class SdkSurveySummary(
        val id: String,
        val name: String,
        val mode: String,
        val source: String,
        val resumableAttemptId: String? = null,
    ) {
        fun toPublic() = studio.usergist.feedback.api.SurveySummary(
            id = id,
            name = name,
            mode = mode,
            source = source,
            resumableAttemptId = resumableAttemptId,
        )
    }

    @kotlinx.serialization.Serializable
    internal data class ResolveSurveyLinkPayload(
        val token: String,
        val anonymousId: String,
        val externalId: String? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class ResolveSurveyLinkResponse(
        val surveyId: String,
        val name: String? = null,
        val consentRequired: Boolean = false,
        val openAccess: Boolean = false,
    )

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
    internal data class SdkInvalidateTokenPayload(
        val anonymousId: String,
        val token: String,
    )

    @kotlinx.serialization.Serializable
    internal data class PushChannelsEnvelope(
        val channels: List<studio.usergist.feedback.push.UserGistPushChannel> = emptyList(),
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

    @kotlinx.serialization.Serializable
    private data class UserStateHistory(
        val version: Int = 1,
        val history: Map<String, List<Long>> = emptyMap(),
    )

    private val trackedWindowsDays: IntArray = intArrayOf(1, 3, 7, 14, 30, 60, 90, 180, 365)
    private const val MAX_HISTORY_PER_EVENT = 200

    // ---------------- Feature Requests (5th pillar) ----------------
    // STATUS: API surface declared; HTTP wiring + UI Fragments tracked in
    // PARITY.md. The methods below are deliberately no-ops or scaffolds —
    // host apps can compile against them today and the runtime fills in
    // when the implementation lands in a follow-up PR.

    private var requestsHandlers: studio.usergist.feedback.api.RequestsHandlers? = null
    private val requestsCache = studio.usergist.feedback.internal.requests.RequestsCache()

    private fun requestsApi(): studio.usergist.feedback.internal.requests.RequestsApi? {
        val api = apiRef.get() ?: return null
        return studio.usergist.feedback.internal.requests.RequestsApi(api, json)
    }

    /** Open the SDK-provided requests board UI. */
    fun openRequestsBoard() {
        if (!initialized.get()) return
        val ctx = appContextRef.get() ?: return
        studio.usergist.feedback.internal.ui.requests.RequestsBoardLauncher.openBoard(ctx)
    }

    /** Open the detail view for a specific request. */
    fun openRequestDetail(requestId: String) {
        if (!initialized.get()) return
        val ctx = appContextRef.get() ?: return
        studio.usergist.feedback.internal.ui.requests.RequestsBoardLauncher
            .openDetail(ctx, requestId)
    }

    /**
     * Submit a new request. Validates client-side per spec §7
     * (title ≤120, description ≤1500); server enforces the same.
     */
    fun submitRequest(
        title: String,
        description: String,
        callback: (Throwable?, studio.usergist.feedback.api.FeatureRequest?) -> Unit,
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
        options: studio.usergist.feedback.api.GetRequestsOptions =
            studio.usergist.feedback.api.GetRequestsOptions(),
        callback: (Throwable?, studio.usergist.feedback.api.GetRequestsResult?) -> Unit,
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
        callback: (Throwable?, studio.usergist.feedback.api.FeatureRequest?) -> Unit,
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
        callback: ((Throwable?, studio.usergist.feedback.api.RequestVote?) -> Unit)? = null,
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
        callback: ((Throwable?, studio.usergist.feedback.api.RequestFollow?) -> Unit)? = null,
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
        callback: (Throwable?, List<studio.usergist.feedback.internal.requests.RequestComment>?) -> Unit,
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
        callback: (Throwable?, studio.usergist.feedback.internal.requests.RequestComment?) -> Unit,
    ) {
        val api = requestsApi() ?: return callback(IllegalStateException("not init"), null)
        val identity = identityRef.get()?.load() ?: return callback(IllegalStateException("identity"), null)
        scope.launch {
            val c = api.postComment(requestId, identity.anonymousId, identity.externalId, body)
            if (c == null) callback(IllegalStateException("postComment failed"), null)
            else callback(null, c)
        }
    }

    /** Edit a comment the viewer authored. Server returns 404 if not theirs. */
    fun editComment(
        requestId: String,
        commentId: String,
        body: String,
        callback: (Throwable?, studio.usergist.feedback.internal.requests.RequestComment?) -> Unit,
    ) {
        if (body.isEmpty() || body.length > 1000) {
            callback(IllegalArgumentException("comment body required, max 1000 chars"), null)
            return
        }
        val api = requestsApi() ?: return callback(IllegalStateException("not init"), null)
        val identity = identityRef.get()?.load() ?: return callback(IllegalStateException("identity"), null)
        scope.launch {
            val c = api.editComment(requestId, commentId, identity.anonymousId, body)
            if (c == null) callback(IllegalStateException("editComment failed"), null)
            else callback(null, c)
        }
    }

    /** Delete a comment the viewer authored. */
    fun deleteComment(
        requestId: String,
        commentId: String,
        callback: (Throwable?) -> Unit = {},
    ) {
        val api = requestsApi() ?: return callback(IllegalStateException("not init"))
        val identity = identityRef.get()?.load() ?: return callback(IllegalStateException("identity"))
        scope.launch {
            val ok = api.deleteComment(requestId, commentId, identity.anonymousId)
            if (!ok) callback(IllegalStateException("deleteComment failed"))
            else callback(null)
        }
    }

    /** Fetch per-app branding (entry label, accent color, etc.). */
    fun getRequestBranding(
        callback: (Throwable?, studio.usergist.feedback.api.RequestBranding?) -> Unit,
    ) {
        val api = requestsApi() ?: return callback(IllegalStateException("not init"), null)
        scope.launch {
            val b = api.branding()
            if (b == null) callback(IllegalStateException("getRequestBranding failed"), null)
            else callback(null, b)
        }
    }

    /** Register host-app callbacks for the request lifecycle. */
    fun setRequestsHandlers(handlers: studio.usergist.feedback.api.RequestsHandlers) {
        requestsHandlers = handlers
    }
}
