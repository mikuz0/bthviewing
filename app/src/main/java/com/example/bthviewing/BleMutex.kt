package com.example.bthviewing

import kotlinx.coroutines.sync.Mutex

/**
 * Единый Mutex для всех GATT-операций приложения.
 * Гарантирует, что в любой момент активен не более одного BluetoothGatt-клиента.
 *
 * Используется в:
 *  - GattConfigClient (настройки, сенсор, DEVID)
 *  - GattMemoClient (чтение истории)
 *  - любых будущих GATT-клиентах
 */
object BleMutex {
    val mutex = Mutex()
}
