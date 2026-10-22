package com.example.bthviewing

object BthomeDecoder {

    private const val ID_PACKET_ID       = 0x00
    private const val ID_BATTERY         = 0x01
    private const val ID_TEMPERATURE     = 0x02
    private const val ID_HUMIDITY        = 0x03
    private const val ID_PRESSURE        = 0x04
    private const val ID_ILLUMINANCE     = 0x05
    private const val ID_DEWPOINT        = 0x08
    private const val ID_COUNT8          = 0x09
    private const val ID_ENERGY24        = 0x0A
    private const val ID_POWER24         = 0x0B
    private const val ID_VOLTAGE         = 0x0C
    private const val ID_PM2x5           = 0x0D
    private const val ID_PM10            = 0x0E
    private const val ID_BOOLEAN         = 0x0F
    private const val ID_SWITCH          = 0x10
    private const val ID_OPENED          = 0x11
    private const val ID_CO2             = 0x12
    private const val ID_TVOC            = 0x13
    private const val ID_MOISTURE16      = 0x14
    private const val ID_LOW_BATTERY     = 0x15
    private const val ID_CHG_BATTERY     = 0x16
    private const val ID_BUTTON          = 0x3A
    private const val ID_COUNT16         = 0x3D
    private const val ID_COUNT32         = 0x3E
    private const val ID_ROTATION        = 0x3F

    /**
     * Длина значения по Object ID. Если ID не описан — null (парсинг прекращается).
     * Значения соответствуют BTHome v2 (bthome.io/format).
     */
    private fun valueLength(id: Int): Int? = when (id) {
        ID_PACKET_ID    -> 1
        ID_BATTERY      -> 1
        ID_TEMPERATURE  -> 2
        ID_HUMIDITY     -> 2
        ID_PRESSURE     -> 3
        ID_ILLUMINANCE  -> 3
        ID_DEWPOINT     -> 2
        ID_COUNT8       -> 1
        ID_ENERGY24     -> 3
        ID_POWER24      -> 3
        ID_VOLTAGE      -> 2
        ID_PM2x5        -> 2
        ID_PM10         -> 2
        ID_BOOLEAN      -> 1
        ID_SWITCH       -> 1
        ID_OPENED       -> 1
        ID_CO2          -> 2
        ID_TVOC         -> 2
        ID_MOISTURE16   -> 2
        ID_LOW_BATTERY  -> 1
        ID_CHG_BATTERY  -> 1
        ID_BUTTON       -> 1
        ID_COUNT16      -> 2
        ID_COUNT32      -> 4
        ID_ROTATION     -> 2
        else            -> null
    }

    fun decode(
        serviceData: ByteArray,
        deviceAddress: String,
        deviceName: String?,
        rssi: Int
    ): BthomeData? {
        if (serviceData.size < 2) return null

        val deviceInfo  = serviceData[0].toInt() and 0xFF
        val version     = (deviceInfo shr 5) and 0x07
        val isEncrypted = (deviceInfo and 0x01) != 0

        if (version != 2) return null
        if (isEncrypted) return null

        var temperature: Float? = null
        var humidity: Float? = null
        var battery: Int? = null
        var voltage: Float? = null

        var index = 1
        while (index < serviceData.size) {
            val objectId = serviceData[index].toInt() and 0xFF
            index++

            val len = valueLength(objectId) ?: break
            if (index + len > serviceData.size) break

            when (objectId) {
                ID_BATTERY     -> battery = serviceData[index].toInt() and 0xFF
                ID_TEMPERATURE -> temperature = readInt16LE(serviceData, index) / 100.0f
                ID_HUMIDITY    -> humidity = readUInt16LE(serviceData, index) / 100.0f
                ID_VOLTAGE     -> voltage = readUInt16LE(serviceData, index) / 1000.0f
                else           -> { /* известен, но не интересует */ }
            }
            index += len
        }

        return BthomeData(
            deviceAddress = deviceAddress,
            deviceName = deviceName,
            temperature = temperature,
            humidity = humidity,
            battery = battery,
            voltage = voltage,
            rssi = rssi
        )
    }

    private fun readInt16LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt16LE(data: ByteArray, offset: Int): Int =
        readInt16LE(data, offset)
}