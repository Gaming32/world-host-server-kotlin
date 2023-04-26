package io.github.gaming32.worldhostserver

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.DoubleArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.lang.Math.toRadians
import kotlin.math.*

@Serializable(LatitudeLongitude.Serializer::class)
data class LatitudeLongitude(val lat: Double, val long: Double) {
    @OptIn(ExperimentalSerializationApi::class)
    class Serializer : KSerializer<LatitudeLongitude> {
        private val delegateSerializer = DoubleArraySerializer()
        override val descriptor = SerialDescriptor("LatitudeLongitude", delegateSerializer.descriptor)

        override fun serialize(encoder: Encoder, value: LatitudeLongitude) =
            encoder.encodeSerializableValue(delegateSerializer, doubleArrayOf(value.lat, value.long))

        override fun deserialize(decoder: Decoder): LatitudeLongitude {
            val array = decoder.decodeSerializableValue(delegateSerializer)
            if (array.size != 2) {
                throw SerializationException("Expected LatitudeLongitude array to have 2 elements, not ${array.size}.")
            }
            return LatitudeLongitude(array[0], array[1])
        }
    }

    fun haversineDistance(other: LatitudeLongitude): Double {
        val x1 = toRadians(lat)
        val y1 = toRadians(long)
        val x2 = toRadians(other.lat)
        val y2 = toRadians(other.long)

        val a = sin((x2 - x1) / 2).pow(2)
            + cos(x1) * cos(x2) * sin((y2 - y1) / 2).pow(2)

        return 2 * asin(min(1.0, sqrt(a)))
    }
}
