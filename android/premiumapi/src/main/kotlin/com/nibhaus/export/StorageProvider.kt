package com.nibhaus.export

/**
 * One sync target. Phase 3's pluggable abstraction (DESIGN.md): exactly one implementation per
 * backend, chosen at export time from the "Sync method" setting — nothing about the target leaks
 * into the export pipeline. [write] overwrites by name, which is what makes re-export idempotent.
 *
 * [id] identifies *which* concrete target this is (folder uri / endpoint), so the idempotency
 * ledger re-exports when the user switches targets instead of stranding files on the old one.
 *
 * Open-core: this contract lives in :premiumapi. Free targets (LocalFolderProvider, LocalOnlyProvider)
 * implement it in :app; the premium TailscalePushProvider implements it in :premium.
 */
interface StorageProvider {
    val id: String

    /** [name] may contain `/` — a type-driven sub-path like `pnb/Work/PNB_Work_Pg038.svg`. Each impl
     *  creates the intermediate folders as needed and overwrites the leaf by name. */
    suspend fun write(name: String, bytes: ByteArray)

    /** Best-effort removal of a previously-written artifact (for deleting a page from the sync
     *  destination). Missing files are not an error. Default no-op so a target that needn't delete
     *  simply ignores it. */
    suspend fun delete(name: String) {}
}

/** Advisory content type from the file extension — covers every artifact the engine emits.
 *  Public (was `internal` in :app) so both the :app and :premium provider impls can reuse it. */
fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "pdf" -> "application/pdf"
    "inkml" -> "application/inkml+xml"
    "md" -> "text/markdown"
    "txt" -> "text/plain"
    else -> "application/json"
}
