package eu.kanade.tachiyomi.extension.model

/**
 * An immutable, consistent view of the installed extensions.
 *
 * An extension can be loaded or not loaded, but never both. Publishing both maps
 * as a single value prevents UI consumers from combining different reload versions.
 */
internal data class InstalledExtensionMaps<Loaded, NotLoaded>(
    val loaded: Map<String, Loaded> = emptyMap(),
    val notLoaded: Map<String, NotLoaded> = emptyMap(),
) {
    fun withLoaded(packageName: String, extension: Loaded): InstalledExtensionMaps<Loaded, NotLoaded> = copy(
        loaded = loaded + (packageName to extension),
        notLoaded = notLoaded - packageName,
    )

    fun withNotLoaded(packageName: String, extension: NotLoaded): InstalledExtensionMaps<Loaded, NotLoaded> = copy(
        loaded = loaded - packageName,
        notLoaded = notLoaded + (packageName to extension),
    )

    fun without(packageName: String): InstalledExtensionMaps<Loaded, NotLoaded> = copy(
        loaded = loaded - packageName,
        notLoaded = notLoaded - packageName,
    )
}
