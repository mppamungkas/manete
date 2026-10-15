package id.my.pamslab.netlogger.permissions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** Seam over the platform permission check so the ViewModel is testable without Android. */
interface PermissionChecker {
    val sdkInt: Int
    fun isGranted(permission: String): Boolean
}

class AndroidPermissionChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PermissionChecker {
    override val sdkInt: Int = Build.VERSION.SDK_INT

    override fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PermissionModule {
    @Binds
    abstract fun bindPermissionChecker(impl: AndroidPermissionChecker): PermissionChecker
}
