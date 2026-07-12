package app.eve.health

/**
 * The seam over the on-phone health hub. The ONE method reads the last-24h window and returns a
 * plain-Kotlin [HealthReadout] — every androidx.health.connect type stays inside the production impl
 * ([HealthConnectReader]), so this interface is trivially faked on the JVM.
 *
 * Contract: NEVER throws. A missing permission or an empty query becomes a [Field.Missing] with the
 * honest reason ([Field.NO_PERMISSION] / [Field.NO_DATA]); it never surfaces as a fabricated value or
 * a swallowed success. The caller ([HealthUploadWorker]) turns the readout into the wire snapshot via
 * the pure [HealthSnapshotAssembler].
 */
interface HealthSnapshotReader {
    suspend fun read(): HealthReadout
}
