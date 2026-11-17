package com.vcameraspace.core.database.converter

import androidx.room.TypeConverter
import com.vcameraspace.core.common.model.ExecutionArchitecture
import com.vcameraspace.core.common.model.VirtualAppStatus

class VCameraTypeConverters {
    @TypeConverter
    fun fromExecutionArchitecture(value: ExecutionArchitecture): String = value.name

    @TypeConverter
    fun toExecutionArchitecture(raw: String): ExecutionArchitecture =
        runCatching { ExecutionArchitecture.valueOf(raw) }
            .getOrDefault(ExecutionArchitecture.SUPPORTED_SANDBOX)

    @TypeConverter
    fun fromVirtualAppStatus(value: VirtualAppStatus): String = value.name

    @TypeConverter
    fun toVirtualAppStatus(raw: String): VirtualAppStatus =
        runCatching { VirtualAppStatus.valueOf(raw) }
            .getOrDefault(VirtualAppStatus.READY)
}
