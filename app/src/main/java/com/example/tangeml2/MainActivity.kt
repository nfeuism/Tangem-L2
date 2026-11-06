package com.example.tangeml2

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.tangeml2.core.log.AppLog
import com.example.tangeml2.core.util.Validators
import com.example.tangeml2.data.card.TangemCardGateway
import com.example.tangeml2.data.qr.QrPayloadParser
import com.example.tangeml2.domain.RecoveryViewModel
import com.example.tangeml2.domain.model.QrPayload
import com.example.tangeml2.ui.screens.RecoveryScreen
import com.example.tangeml2.ui.theme.TangemL2Theme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Single-activity host.
 *
 * Extends [FragmentActivity] because the Tangem SDK presents its NFC session in a
 * `DialogFragment` (`NfcSessionDialog`) and resolves it from the hosting activity.
 */
class MainActivity : FragmentActivity() {

    private val cardGateway: TangemCardGateway by lazy { TangemCardGateway(this) }

    private val viewModel: RecoveryViewModel by viewModels {
        viewModelFactory {
            initializer { RecoveryViewModel(application, cardGateway) }
        }
    }

    private enum class ScanPurpose { RECIPIENT, CONTRACT }

    private var scanPurpose = ScanPurpose.RECIPIENT

    /** Registered as a field so it happens before the activity reaches STARTED. */
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result?.contents
        if (!raw.isNullOrBlank()) handleScanned(raw.trim(), scanPurpose)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLog.app("ui", "MainActivity onCreate")

        setContent {
            TangemL2Theme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                RecoveryScreen(
                    state = state,
                    actions = viewModel,
                    onScanRecipientQr = { launchScan(ScanPurpose.RECIPIENT) },
                    onScanContractQr = { launchScan(ScanPurpose.CONTRACT) },
                )
            }
        }
    }

    private fun launchScan(purpose: ScanPurpose) {
        scanPurpose = purpose
        val options = ScanOptions()
            .setOrientationLocked(false)
            .setBeepEnabled(false)
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(
                if (purpose == ScanPurpose.CONTRACT) {
                    "扫描合约调用二维码"
                } else {
                    "扫描收款地址二维码"
                },
            )
        runCatching { scanLauncher.launch(options) }
            .onFailure { AppLog.err("ui", "无法启动扫码", it) }
    }

    /**
     * A recipient scan should never open the contract-review sheet: only a scan that
     * was explicitly started for contract signing goes through the full parser path.
     */
    private fun handleScanned(raw: String, purpose: ScanPurpose) {
        AppLog.app("ui", "扫码($purpose) ${raw.take(160)}")
        if (purpose == ScanPurpose.CONTRACT) {
            viewModel.onQrScanned(raw)
            return
        }
        when (val payload = QrPayloadParser.parse(raw)) {
            is QrPayload.Address ->
                viewModel.setRecipient(Validators.toChecksumAddress(payload.address))

            is QrPayload.ContractCall ->
                if (payload.call.to.isNotBlank()) {
                    viewModel.setRecipient(Validators.toChecksumAddress(payload.call.to))
                } else {
                    viewModel.onQrScanned(raw)
                }

            is QrPayload.Unsupported -> viewModel.onQrScanned(raw)
        }
    }
}
