package dev.molasses.data.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import dev.molasses.CycleResetPolicyProto
import dev.molasses.CycleState
import dev.molasses.core.session.DefaultTargets
import java.io.InputStream
import java.io.OutputStream

object CycleStateSerializer : Serializer<CycleState> {

    const val FILE_NAME = "cycle_state.pb"

    // schema_version is stamped here so a fresh install is never seen as a
    // pre-migration file by CycleStateStore.migrate().
    override val defaultValue: CycleState = CycleState.newBuilder()
        .setResetPolicy(CycleResetPolicyProto.FIXED_WINDOW_6H)
        .setSchemaVersion(CycleStateStore.SCHEMA_VERSION)
        .addAllTargetPackages(DEFAULT_TARGETS)
        .build()

    override suspend fun readFrom(input: InputStream): CycleState =
        try {
            CycleState.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            // Losing hot state means the ladder restarts at tier 0, which is
            // the forgiving direction; the Room ledger still holds the history
            // for reconstruction, so this is recoverable rather than fatal.
            throw CorruptionException("cycle_state.pb is unreadable", e)
        }

    override suspend fun writeTo(t: CycleState, output: OutputStream) = t.writeTo(output)
}

/**
 * The apps tracked until the user chooses a list. Held in [DefaultTargets],
 * which is pure, so its migration and the declared profile can be tested
 * against it.
 */
val DEFAULT_TARGETS: List<String> = DefaultTargets.CURRENT
