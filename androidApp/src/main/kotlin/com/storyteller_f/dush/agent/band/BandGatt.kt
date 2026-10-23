@file:Suppress("DEPRECATION", "MissingPermission")
package com.storyteller_f.dush.agent.band

import android.bluetooth.*
import android.content.Context
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException
import java.util.UUID

/** Callback-only Android boundary. All GATT operations are serialized by its connection owner. */
internal class BandGatt(private val context: Context) : Closeable {
    sealed interface Event {
        data class Changed(val uuid: UUID, val value: ByteArray) : Event
        data class Disconnected(val status: Int) : Event
    }
    private data class Operation(val kind: String, val status: Int, val value: ByteArray? = null)
    private val operations = Channel<Operation>(16)
    val incoming = Channel<Event>(256)
    private var gatt: BluetoothGatt? = null
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (status == 0 && state == BluetoothProfile.STATE_CONNECTED) complete("connect", 0)
            else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                operations.close(IOException("Bluetooth disconnected ($status)"))
                incoming.trySend(Event.Disconnected(status))
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = complete("services", status)
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            complete("mtu", if (status == 0 && mtu >= 247) 0 else -1)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = complete("subscribe", status)
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = complete("write", status)
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) = complete("read", status, value.copyOf())
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = complete("read", status, characteristic.value?.copyOf())
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = changed(characteristic.uuid, value)
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) = changed(characteristic.uuid, characteristic.value ?: byteArrayOf())
    }
    private fun complete(kind: String, status: Int, value: ByteArray? = null) {
        if (operations.trySend(Operation(kind, status, value)).isFailure) close()
    }
    private fun changed(uuid: UUID, bytes: ByteArray) {
        // Dropping a BLE fragment corrupts reassembly. Force reconnection on overflow instead.
        if (incoming.trySend(Event.Changed(uuid, bytes.copyOf())).isFailure) close()
    }
    private suspend fun await(kind: String) = withTimeout(20_000) {
        val result = operations.receive()
        check(result.kind == kind && (result.status == 0 || kind == "read")) { "Bluetooth $kind failed (${result.status})" }
        result
    }
    suspend fun connect(address: String) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        check(manager.adapter?.isEnabled == true) { "请开启蓝牙" }
        gatt = requireNotNull(manager.adapter.getRemoteDevice(address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE))
        await("connect")
        check(requireNotNull(gatt).discoverServices()); await("services")
        check(requireNotNull(gatt).requestMtu(247)); await("mtu")
        subscribe(READ)
    }
    private fun characteristic(id: UUID): BluetoothGattCharacteristic = requireNotNull(gatt).services.firstNotNullOfOrNull { it.getCharacteristic(id) }
        ?: error("手环缺少所需的蓝牙通道")
    suspend fun subscribe(id: UUID, required: Boolean = true): Boolean {
        val g = requireNotNull(gatt)
        val c = g.services.firstNotNullOfOrNull { it.getCharacteristic(id) }
        if (c == null) { check(!required) { "外设缺少所需通道" }; return false }
        if (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) {
            check(!required) { "外设通道不支持通知" }; return false
        }
        check(g.setCharacteristicNotification(c, true))
        val d = requireNotNull(c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")))
        check(g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS)
        await("subscribe"); return true
    }
    suspend fun read(id: UUID): ByteArray? {
        val g = requireNotNull(gatt)
        val c = g.services.firstNotNullOfOrNull { it.getCharacteristic(id) } ?: return null
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) return null
        check(g.readCharacteristic(c))
        val result = await("read")
        return result.value.takeIf { result.status == BluetoothGatt.GATT_SUCCESS }
    }
    suspend fun write(id: UUID, value: ByteArray) {
        val g = requireNotNull(gatt)
        val c = characteristic(id)
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        check(g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS)
        await("write")
    }
    override fun close() {
        val old = gatt; gatt = null
        try { old?.disconnect() } finally { old?.close() }
        operations.close(); incoming.close()
    }
    companion object {
        val WRITE: UUID = UUID.fromString("00000016-0000-3512-2118-0009af100700")
        val READ: UUID = UUID.fromString("00000017-0000-3512-2118-0009af100700")
    }
}
