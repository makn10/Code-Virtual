package com.example

import android.app.Application
import com.vcameraspace.core.common.coroutines.DefaultDispatcherProvider
import com.vcameraspace.core.common.coroutines.DispatcherProvider
import com.vcameraspace.core.database.VCameraDatabase
import com.vcameraspace.core.database.repository.RoomSpaceRepository
import com.vcameraspace.core.database.repository.SpaceRepository
import com.vcameraspace.core.datastore.DataStoreUserPreferencesRepository
import com.vcameraspace.core.datastore.UserPreferencesRepository
import com.vcameraspace.core.media.CameraConfigurationRepository
import com.vcameraspace.core.media.MediaInspector
import com.vcameraspace.core.media.RoomCameraConfigurationRepository
import com.vcameraspace.core.permissions.RuntimePermissionManager
import com.vcameraspace.virtualengine.api.VirtualAppInstaller
import com.vcameraspace.virtualengine.api.VirtualCameraProvider
import com.vcameraspace.virtualengine.api.VirtualEngine
import com.vcameraspace.virtualengine.api.VirtualPackageManager
import com.vcameraspace.virtualengine.api.VirtualStorageManager
import com.vcameraspace.virtualengine.impl.AndroidCompatibilityChecker
import com.vcameraspace.virtualengine.impl.AndroidVirtualAppInstaller
import com.vcameraspace.virtualengine.impl.AndroidVirtualLocationProvider
import com.vcameraspace.virtualengine.impl.HybridVirtualEngine
import com.vcameraspace.virtualengine.impl.ManagedVirtualProcessManager
import com.vcameraspace.virtualengine.impl.RoomVirtualCameraProvider
import com.vcameraspace.virtualengine.impl.RoomVirtualPackageManager
import com.vcameraspace.virtualengine.impl.ScopedVirtualStorageManager

class AppContainer(application: Application) {
    val applicationContext = application
    val dispatchers: DispatcherProvider = DefaultDispatcherProvider()

    val userPreferencesRepository: UserPreferencesRepository =
        DataStoreUserPreferencesRepository(application)

    val database: VCameraDatabase = VCameraDatabase.create(application)

    val spaceRepository: SpaceRepository = RoomSpaceRepository(
        context = application,
        spaceDao = database.spaceDao(),
        virtualAppDao = database.virtualAppDao(),
        userPreferencesRepository = userPreferencesRepository,
        dispatchers = dispatchers
    )

    val mediaInspector: MediaInspector = MediaInspector(
        context = application,
        dispatchers = dispatchers
    )

    val cameraConfigRepository: CameraConfigurationRepository = RoomCameraConfigurationRepository(
        cameraConfigDao = database.cameraConfigDao(),
        dispatchers = dispatchers
    )

    val permissionManager: RuntimePermissionManager = RuntimePermissionManager(application)

    val storageManager: VirtualStorageManager = ScopedVirtualStorageManager(
        context = application,
        dispatchers = dispatchers
    )

    val compatibilityChecker: AndroidCompatibilityChecker = AndroidCompatibilityChecker(
        context = application,
        dispatchers = dispatchers
    )

    val virtualPackageManager: VirtualPackageManager = RoomVirtualPackageManager(
        virtualAppDao = database.virtualAppDao(),
        dispatchers = dispatchers
    )

    val processManager: ManagedVirtualProcessManager = ManagedVirtualProcessManager(
        context = application,
        virtualAppDao = database.virtualAppDao(),
        dispatchers = dispatchers
    )

    val appInstaller: VirtualAppInstaller = AndroidVirtualAppInstaller(
        context = application,
        virtualAppDao = database.virtualAppDao(),
        installationDao = database.installationDao(),
        compatibilityChecker = compatibilityChecker,
        storageManager = storageManager,
        dispatchers = dispatchers
    )

    val virtualEngine: VirtualEngine = HybridVirtualEngine(storageManager)

    val virtualCameraProvider: VirtualCameraProvider = RoomVirtualCameraProvider(
        cameraConfigDao = database.cameraConfigDao()
    )

    val virtualLocationProvider: AndroidVirtualLocationProvider = AndroidVirtualLocationProvider(
        context = application,
        locationConfigDao = database.locationConfigDao(),
        dispatchers = dispatchers
    )
}

class VCameraApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
