package co.fallsoft.pocket

/** Catalog availability is independent of background notifications/activity reads.
 * Owned by the current environment; fetching does not mark messages read. */
sealed interface CatalogLoadState {
    data object Loading:CatalogLoadState
    data object Ready:CatalogLoadState
    data class Failed(val message:String):CatalogLoadState
}
