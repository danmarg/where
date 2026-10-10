package net.af0.where

import dev.icerock.moko.resources.desc.Resource
import dev.icerock.moko.resources.desc.StringDesc
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.InviteExpiredException
import net.af0.where.e2ee.RawKeyValueStorage
import net.af0.where.e2ee.UserStore
import net.af0.where.shared.MR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationRepositoryTest {
    private class MemoryStorage : RawKeyValueStorage {
        val map = mutableMapOf<String, String>()

        override fun getString(key: String): String? = map[key]

        override fun putString(
            key: String,
            value: String,
        ) {
            map[key] = value
        }
    }

    @Test
    fun onConnectionError_inviteExpired_showsInviteExpiredMessage() {
        val repo = LocationRepository(UserStore(MemoryStorage()))
        repo.onConnectionError(InviteExpiredException())
        val status = repo.connectionStatus.value
        assertTrue(status is ConnectionStatus.Error, "expected an error, was $status")
        assertEquals(StringDesc.Resource(MR.strings.invite_expired), status.message)
    }
}
