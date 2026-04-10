package com.proactiveai.extreme.core.context.engine

import android.content.Context
import com.proactiveai.extreme.core.context.ContextPlugin
import com.proactiveai.extreme.core.context.plugins.AudioAmbientPlugin
import com.proactiveai.extreme.core.context.plugins.CommunicationContextPlugin
import com.proactiveai.extreme.core.context.plugins.HealthConnectPlugin
import com.proactiveai.extreme.core.context.plugins.LocationMotionPlugin
import com.proactiveai.extreme.core.context.plugins.MediaNotebookPlugin
import com.proactiveai.extreme.core.context.plugins.NearbyConnectivityPlugin
import com.proactiveai.extreme.core.context.plugins.NotificationUsagePlugin
import com.proactiveai.extreme.core.context.plugins.SensorFusionContextPlugin
import com.proactiveai.extreme.core.context.plugins.SystemDeviceContextPlugin
import com.proactiveai.extreme.data.ExtremeDefaults

object DefaultContextPluginFactory {
    fun create(context: Context): List<ContextPlugin> {
        val descriptors = ExtremeDefaults.plugins().associateBy { it.id }

        return buildList {
            descriptors["location_motion"]?.let { add(LocationMotionPlugin(context, it)) }
            descriptors["sensor_fusion"]?.let { add(SensorFusionContextPlugin(context, it)) }
            descriptors["audio_ambient"]?.let { add(AudioAmbientPlugin(context, it)) }
            descriptors["system_device"]?.let { add(SystemDeviceContextPlugin(context, it)) }
            descriptors["health_connect"]?.let { add(HealthConnectPlugin(context, it)) }
            descriptors["notification_usage"]?.let { add(NotificationUsagePlugin(context, it)) }
            descriptors["media_notebook"]?.let { add(MediaNotebookPlugin(context, it)) }
            descriptors["communication"]?.let { add(CommunicationContextPlugin(context, it)) }
            descriptors["nearby_connectivity"]?.let { add(NearbyConnectivityPlugin(context, it)) }
        }
    }
}
