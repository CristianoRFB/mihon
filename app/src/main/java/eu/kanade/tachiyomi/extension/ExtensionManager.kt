package eu.kanade.tachiyomi.extension

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.api.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstalledExtensionMaps
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import java.util.Locale

/**
 * The manager of extensions installed as another apk which extend the available sources. It handles
 * the retrieval of remotely available extensions as well as installing, updating and removing them.
 * To avoid malicious distribution, every extension must be signed and it will only be loaded if its
 * signature is trusted, otherwise the user will be prompted with a warning to trust it before being
 * loaded.
 */
@Inject
@SingleIn(AppScope::class)
class ExtensionManager(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val preferences: SourcePreferences,
    private val trustExtension: TrustExtension,
    private val extensionStoreRepository: ExtensionStoreRepository,
    private val updateExtensionStores: UpdateExtensionStores,
    private val installer: ExtensionInstaller,
    private val extensionUpdateNotifier: ExtensionUpdateNotifier,
) {

    private val initialized = CompletableDeferred<Unit>()

    // A full load replaces both maps, so install events wait for it and apply on top instead of being overwritten
    private val loadMutex = Mutex()

    @Volatile
    private var stores = emptyList<ExtensionStore>()

    // Loaded and not-loaded extensions must be published together: trusting an extension
    // moves it between the two groups without temporarily losing or duplicating it.
    private val installedExtensionsState = MutableStateFlow(
        InstalledExtensionMaps<Extension.Loaded, Extension.NotLoaded>(),
    )
    val installedExtensionsFlow = installedExtensionsState.onStart { initialized.await() }
    val loadedExtensionsFlow = installedExtensionsFlow.map { it.loaded.values.toList() }

    // Every store's listing, since more than one store can list the same extension
    private val availableExtensionListFlow = MutableStateFlow(emptyList<Extension.Available>())

    // Stores sharing a signing key serve the same apks, so only the newest of their listings is shown. Stores
    // with different keys offer different apks, each installable, so each keeps its own.
    val availableExtensionsFlow = availableExtensionListFlow
        .map { extensions ->
            extensions
                .groupBy { it.pkgName to it.store.signingKey }
                .values
                .map { listings ->
                    listings.maxWith(compareBy<Extension.Available> { it.versionCode }.thenBy { it.libVersion })
                }
        }
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val notLoadedExtensionsFlow = installedExtensionsFlow.map { it.notLoaded.values.toList() }

    init {
        scope.launch(Dispatchers.IO) {
            loadExtensions()
            ExtensionInstallReceiver(InstallationListener(), scope).register(context)

            // Everything the load decision rests on can change while running, so decide again
            merge(
                trustExtension.changes(),
                preferences.enabledContentWarnings.changes().distinctUntilChanged().drop(1).map {},
                preferences.applyContentWarningsToInstalled.changes().distinctUntilChanged().drop(1).map {},
            )
                .collectLatest { loadExtensions() }
        }

        // Extensions are only reloaded when the set of signing keys changes, which misses a store being
        // removed while another with the same key stays, or a store being renamed
        scope.launch(Dispatchers.IO) {
            initialized.await()
            extensionStoreRepository.getAllAsFlow().collect(::assignStores)
        }
    }

    private var subLanguagesEnabledOnFirstRun = preferences.enabledLanguages.isSet()

    suspend fun getLoadedExtensions(): List<Extension.Loaded> {
        initialized.await()
        return installedExtensionsState.value.loaded.values.toList()
    }

    suspend fun getNotLoadedExtensions(): List<Extension.NotLoaded> {
        initialized.await()
        return installedExtensionsState.value.notLoaded.values.toList()
    }

    suspend fun getExtensionPackage(sourceId: Long): String? {
        return getLoadedExtensions().find { extension ->
            extension.sources.any { it.id == sourceId }
        }
            ?.pkgName
    }

    fun getExtensionPackageAsFlow(sourceId: Long): Flow<String?> {
        return loadedExtensionsFlow.map { extensions ->
            extensions.find { extension ->
                extension.sources.any { it.id == sourceId }
            }
                ?.pkgName
        }
    }

    private var availableExtensionsSourcesData: Map<Long, StubSource> = emptyMap()

    private fun setupAvailableExtensionsSourcesDataMap(extensions: List<Extension.Available>) {
        if (extensions.isEmpty()) return
        availableExtensionsSourcesData = extensions
            .flatMap { ext -> ext.sources.map { it.toStubSource() } }
            .associateBy { it.id }
    }

    fun getSourceData(id: Long) = availableExtensionsSourcesData[id]

    /**
     * Loads and registers the installed extensions. Safe to call again: every extension is judged
     * again, so one can move between loaded and not loaded in either direction, while extensions
     * that still pass keep the instances they already had.
     */
    private suspend fun loadExtensions() = loadMutex.withLock {
        try {
            val extensions = ExtensionLoader.loadExtensions(context, installedExtensionsState.value.loaded)

            // One StateFlow emission for the complete classification. A subscriber must never
            // observe a newly loaded extension alongside an outdated not-loaded entry.
            installedExtensionsState.value = InstalledExtensionMaps(
                loaded = extensions.filterIsInstance<Extension.Loaded>().associateBy { it.pkgName },
                notLoaded = extensions.filterIsInstance<Extension.NotLoaded>().associateBy { it.pkgName },
            )

            // Newly loaded extensions have no status derived from the store index yet
            refreshStatuses()
        } catch (e: CancellationException) {
            // collectLatest cancels superseded reloads when trust changes rapidly.
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to load extensions" }
        } finally {
            // Release anything waiting on the extensions whether or not the load worked
            initialized.complete(Unit)
        }
    }

    /**
     * Finds the available extensions in the stores and updates [availableExtensionListFlow].
     */
    suspend fun findAvailableExtensions() {
        val extensions: List<Extension.Available> = try {
            fetchExtensions()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            withContext(Dispatchers.Main) { context.toast(MR.strings.extension_api_error) }
            return
        }

        setAvailableExtensions(extensions)
    }

    /**
     * Refreshes the stores and their listings like [findAvailableExtensions], then notifies about the
     * installed extensions that have an update. Stays silent when the stores can't be reached.
     */
    suspend fun checkForUpdates() {
        val extensions = try {
            updateExtensionStores()
            fetchExtensions()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return
        }

        initialized.await()
        setAvailableExtensions(extensions)

        val installed = installedExtensionsState.value
        val names = (installed.loaded.values + installed.notLoaded.values)
            .filter { it.hasUpdate }
            .map { it.name }
        if (names.isNotEmpty()) {
            extensionUpdateNotifier.promptUpdates(names)
        }
    }

    private suspend fun fetchExtensions(): List<Extension.Available> {
        return extensionStoreRepository.fetchExtensions()
    }

    private fun setAvailableExtensions(extensions: List<Extension.Available>) {
        enableAdditionalSubLanguages(extensions)

        availableExtensionListFlow.value = extensions
        refreshStatuses()
        setupAvailableExtensionsSourcesDataMap(extensions)
    }

    /**
     * Enables the additional sub-languages in the app first run. This addresses
     * the issue where users still need to enable some specific languages even when
     * the device language is inside that major group. As an example, if a user
     * has a zh device language, the app will also enable zh-Hans and zh-Hant.
     *
     * If the user have already changed the enabledLanguages preference value once,
     * the new languages will not be added to respect the user enabled choices.
     */
    private fun enableAdditionalSubLanguages(extensions: List<Extension.Available>) {
        if (subLanguagesEnabledOnFirstRun || extensions.isEmpty()) {
            return
        }

        // Use the source lang as some aren't present on the extension level.
        val availableLanguages = extensions
            .flatMap(Extension.Available::sources)
            .distinctBy(Extension.Available.Source::lang)
            .map(Extension.Available.Source::lang)

        val deviceLanguage = Locale.getDefault().language
        val defaultLanguages = preferences.enabledLanguages.defaultValue()
        val languagesToEnable = availableLanguages.filter {
            it != deviceLanguage && it.startsWith(deviceLanguage)
        }

        preferences.enabledLanguages.set(defaultLanguages + languagesToEnable)
        subLanguagesEnabledOnFirstRun = true
    }

    /**
     * Returns a flow of the installation process for the given extension. It will complete
     * once the extension is installed or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The extension to be installed.
     */
    fun installExtension(extension: Extension.Available): Flow<InstallStep> {
        return installer.downloadAndInstall(extension)
    }

    /**
     * Returns a flow of the installation process for the given extension. It will complete
     * once the extension is updated or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The extension to be updated.
     */
    fun updateExtension(extension: Extension.Installed): Flow<InstallStep> {
        val update = extension.findUpdate(availableExtensionListFlow.value) ?: return emptyFlow()
        val isUpdateForPrivatelyInstalled = !extension.isShared
        return installer.downloadAndInstall(update, isUpdateForPrivatelyInstalled)
    }

    fun cancelInstallUpdateExtension(extension: Extension) {
        installer.cancelInstall(extension.pkgName)
    }

    /**
     * Sets to "installing" status of an extension installation.
     *
     * @param downloadId The id of the download.
     */
    fun setInstalling(downloadId: Long) {
        installer.updateInstallStep(downloadId, InstallStep.Installing)
    }

    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        installer.updateInstallStep(downloadId, step)
    }

    /**
     * Uninstalls the extension that matches the given package name.
     *
     * @param extension The extension to uninstall.
     */
    fun uninstallExtension(extension: Extension.Installed) {
        installer.uninstallApk(extension.pkgName)
    }

    /**
     * Adds the given extension to the list of trusted extensions. It also loads in background the
     * now trusted extensions.
     *
     * @param extension the extension to trust
     */
    fun trust(extension: Extension.NotLoaded) {
        // Use the current state instead of a possibly stale UI item. A package may have
        // been replaced or reclassified while the confirmation dialog was open.
        val current = installedExtensionsState.value.notLoaded[extension.pkgName] ?: return
        val reason = current.reason as? Extension.NotLoaded.Reason.Untrusted ?: return

        // Loading it again is left to the reload triggered by the trust change.
        trustExtension.trust(current.pkgName, current.versionCode, reason.signatureHash)
    }

    /**
     * Registers the given extension in this and the source managers.
     *
     * @param extension The extension to be registered.
     */
    private fun registerExtension(extension: Extension.Loaded) {
        installedExtensionsState.update { it.withLoaded(extension.pkgName, extension) }
    }

    /**
     * Unregisters the extension in this and the source managers given its package name. Note this
     * method is called for every uninstalled application in the system.
     *
     * @param pkgName The package name of the uninstalled application.
     */
    private fun unregisterExtension(pkgName: String) {
        installedExtensionsState.update { it.without(pkgName) }
    }

    /**
     * Listener which receives events of the extensions being installed, updated or removed.
     */
    private inner class InstallationListener : ExtensionInstallReceiver.Listener {

        override suspend fun onExtensionLoaded(extension: Extension.Loaded) = loadMutex.withLock {
            registerExtension(extension)
            refreshStatuses()
        }

        override suspend fun onExtensionNotLoaded(extension: Extension.NotLoaded) = loadMutex.withLock {
            installedExtensionsState.update { it.withNotLoaded(extension.pkgName, extension) }
            refreshStatuses()
        }

        override suspend fun onPackageUninstalled(pkgName: String) = loadMutex.withLock {
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            unregisterExtension(pkgName)
            updatePendingUpdatesCount()
        }
    }

    /**
     * Derives what the store listings say about every installed extension, loaded or not. Without any
     * listing there's nothing to derive from, so they're left as they are rather than marked obsolete.
     */
    private fun refreshStatuses() {
        val available = availableExtensionListFlow.value
        if (available.isNotEmpty()) {
            installedExtensionsState.update { current ->
                current.copy(
                    loaded = current.loaded.mapValues { (_, extension) ->
                        val listing = extension.findListing(available)
                        extension.copy(
                            hasUpdate = extension.findUpdate(available) != null,
                            isObsolete = listing == null,
                            store = if (stores.isEmpty()) extension.store else extension.pickStore(),
                        )
                    },
                    notLoaded = current.notLoaded.mapValues { (_, extension) ->
                        extension.copy(
                            hasUpdate = extension.findUpdate(available) != null,
                            store = if (stores.isEmpty()) extension.store else extension.pickStore(),
                        )
                    },
                )
            }
        }
        updatePendingUpdatesCount()
    }

    /**
     * Several stores can share a signing key, so one that lists the extension names where it comes from better
     * than whichever of them was added first.
     */
    private fun Extension.Installed.pickStore(): ExtensionStore? {
        val signingStores = stores.filter { it.signingKey in signatures }
        val listedBy = availableExtensionListFlow.value
            .filter { it.pkgName == pkgName }
            .mapTo(HashSet()) { it.store.indexUrl }
        return signingStores.firstOrNull { it.indexUrl in listedBy } ?: signingStores.firstOrNull()
    }

    private fun assignStores(stores: List<ExtensionStore>) {
        this.stores = stores
        installedExtensionsState.update { extensions ->
            extensions.copy(
                loaded = extensions.loaded.mapValues { (_, extension) ->
                    extension.copy(store = extension.pickStore())
                },
                notLoaded = extensions.notLoaded.mapValues { (_, extension) ->
                    extension.copy(store = extension.pickStore())
                },
            )
        }
    }

    private fun updatePendingUpdatesCount() {
        val installed = installedExtensionsState.value
        val pendingUpdateCount = (installed.loaded.values + installed.notLoaded.values)
            .count { it.hasUpdate }
        preferences.extensionUpdatesCount.set(pendingUpdateCount)
        if (pendingUpdateCount == 0) {
            extensionUpdateNotifier.dismiss()
        }
    }

}
