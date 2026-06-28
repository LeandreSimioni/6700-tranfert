package fr.simioni.a6700transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

class UsbReceiver : BroadcastReceiver() {

    companion object {
        private const val SONY_VID = 0x054C
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = getDevice(intent) ?: return
        if (device.vendorId != SONY_VID) return
        TransferLog.add(context, "[USB] Sony detecte: ${device.productName} (PID=${device.productId.toString(16)})")
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getLong(MainActivity.KEY_LAST_TRANSFER, -1L) == -1L)
            TransferLog.add(context, "[USB] Date non configuree - ouvrez l'app")
        else
            TransferLog.add(context, "[USB] Sony detecte - transfert MSC en attente du montage volume")
    }

    @Suppress("DEPRECATION")
    private fun getDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
