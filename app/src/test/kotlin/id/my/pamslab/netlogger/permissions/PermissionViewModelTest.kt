package id.my.pamslab.netlogger.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
private const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
private const val PHONE = Manifest.permission.READ_PHONE_STATE
private const val NOTIFY = Manifest.permission.POST_NOTIFICATIONS

private class FakeChecker(override val sdkInt: Int, var granted: Set<String> = emptySet()) : PermissionChecker {
    override fun isGranted(permission: String) = permission in granted
}

class PermissionViewModelTest {

    private fun PermissionUiState.status(group: PermissionGroup) = groups.single { it.group == group }.status

    @Test
    fun `nothing granted requests everything and cannot log`() {
        val vm = PermissionViewModel(FakeChecker(sdkInt = 35))

        assertFalse(vm.state.value.canLog)
        assertEquals(listOf(FINE, COARSE, PHONE, NOTIFY), vm.permissionsToRequest())
        assertEquals(PermissionStatus.DENIED, vm.state.value.status(PermissionGroup.LOCATION))
    }

    @Test
    fun `logging needs location and phone but not notifications`() {
        val vm = PermissionViewModel(FakeChecker(sdkInt = 35, granted = setOf(FINE, COARSE, PHONE)))

        assertTrue(vm.state.value.canLog)
        assertFalse(vm.state.value.allResolved)
        assertEquals(listOf(NOTIFY), vm.permissionsToRequest())
    }

    @Test
    fun `approximate location is not enough to log`() {
        val vm = PermissionViewModel(FakeChecker(sdkInt = 35, granted = setOf(COARSE, PHONE, NOTIFY)))

        assertEquals(PermissionStatus.APPROXIMATE_ONLY, vm.state.value.status(PermissionGroup.LOCATION))
        assertFalse(vm.state.value.canLog)
        assertEquals(listOf(FINE), vm.permissionsToRequest())
    }

    @Test
    fun `notifications do not apply below Android 13`() {
        val vm = PermissionViewModel(FakeChecker(sdkInt = 32, granted = setOf(FINE, COARSE, PHONE)))

        assertEquals(PermissionStatus.NOT_APPLICABLE, vm.state.value.status(PermissionGroup.NOTIFICATIONS))
        assertTrue(vm.state.value.allResolved)
        assertTrue(vm.permissionsToRequest().isEmpty())
    }

    @Test
    fun `denial without rationale is permanent and is not requested again`() {
        val checker = FakeChecker(sdkInt = 35, granted = setOf(FINE, COARSE))
        val vm = PermissionViewModel(checker)

        vm.onPermissionResult(mapOf(PHONE to false, NOTIFY to false)) { it == NOTIFY }

        assertEquals(PermissionStatus.PERMANENTLY_DENIED, vm.state.value.status(PermissionGroup.PHONE))
        assertEquals(PermissionStatus.DENIED, vm.state.value.status(PermissionGroup.NOTIFICATIONS))
        assertTrue(vm.state.value.anyPermanentlyDenied)
        assertEquals(listOf(NOTIFY), vm.permissionsToRequest())
    }

    @Test
    fun `granting in system settings clears permanent denial on refresh`() {
        val checker = FakeChecker(sdkInt = 35, granted = setOf(FINE, COARSE))
        val vm = PermissionViewModel(checker)
        vm.onPermissionResult(mapOf(PHONE to false)) { false }

        checker.granted = checker.granted + PHONE
        vm.refresh()

        assertEquals(PermissionStatus.GRANTED, vm.state.value.status(PermissionGroup.PHONE))
        assertFalse(vm.state.value.anyPermanentlyDenied)
        assertTrue(vm.state.value.canLog)
    }

    @Test
    fun `approximate location with fine permanently denied points to settings`() {
        val checker = FakeChecker(sdkInt = 35, granted = setOf(COARSE, PHONE, NOTIFY))
        val vm = PermissionViewModel(checker)

        vm.onPermissionResult(mapOf(FINE to false)) { false }

        assertEquals(PermissionStatus.PERMANENTLY_DENIED, vm.state.value.status(PermissionGroup.LOCATION))
        assertTrue(vm.permissionsToRequest().isEmpty())
    }
}
